package com.aadiinfo.nightwatch.data.repository

import com.aadiinfo.nightwatch.domain.model.AlertEvent
import com.aadiinfo.nightwatch.domain.model.GlucoseReading
import com.aadiinfo.nightwatch.domain.model.LivePredictions
import com.aadiinfo.nightwatch.domain.model.Patient
import com.aadiinfo.nightwatch.domain.model.PatientPhysiology
import com.aadiinfo.nightwatch.domain.model.Thresholds
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.functions
import com.google.firebase.Firebase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Thin client over the Firebase backend described in backend/README.md.
 * All the reliability-critical polling happens server-side in
 * `pollGlucose`; this repository only reads what that function wrote and
 * calls a few owner-gated Cloud Functions for setup actions.
 */
class PatientRepository(
    private val firestore: FirebaseFirestore = Firebase.firestore,
    private val functions: FirebaseFunctions = Firebase.functions
) {

    fun patientsForUser(uid: String): Flow<List<Patient>> =
        firestore.collection("patients")
            .whereArrayContains("memberUids", uid)
            .snapshotFlow()
            .map { snapshot -> snapshot.documents.mapNotNull { it.toPatient() } }

    fun observePatient(patientId: String): Flow<Patient?> =
        firestore.collection("patients").document(patientId)
            .snapshotFlow()
            .map { it?.toPatient() }

    // Personal to each member, not shared per patient - each family member
    // sets their own alarm/alert thresholds and display range. Lives as a
    // `thresholds` map field on the member's own doc, readable by the whole
    // family at the Firestore level (see firestore.rules), but this app only
    // ever asks for the signed-in member's own uid.
    @Suppress("UNCHECKED_CAST")
    fun observeThresholds(patientId: String, uid: String): Flow<Thresholds> =
        firestore.collection("patients").document(patientId)
            .collection("members").document(uid)
            .snapshotFlow()
            .map { (it?.get("thresholds") as? Map<String, Any?>)?.toThresholds() ?: Thresholds() }

    // Shared across the whole family - see PatientPhysiology's doc comment
    // for why this is patient-level rather than per-member like Thresholds.
    fun observePatientPhysiology(patientId: String): Flow<PatientPhysiology> =
        firestore.collection("patients").document(patientId)
            .collection("thresholds").document("current")
            .snapshotFlow()
            .map { it?.toPatientPhysiology() ?: PatientPhysiology() }

    // Computed server-side (backend/functions-predict/predictors.py) - a
    // plain read, no on-device computation.
    fun observeLivePredictions(patientId: String): Flow<LivePredictions> =
        firestore.collection("patients").document(patientId)
            .collection("livePredictions").document("current")
            .snapshotFlow()
            .map { it?.toLivePredictions() ?: LivePredictions() }

    // liveReading/current is overwritten every poll cycle regardless of
    // whether the reading is new (see index.ts's writeCurrentReading) - the
    // same object sendReadingStatusPush pushes to the notification, so this
    // can never diverge from it the way querying the readings history by a
    // tie-prone field once did.
    fun observeLatestReading(patientId: String): Flow<GlucoseReading?> =
        firestore.collection("patients").document(patientId)
            .collection("liveReading").document("current")
            .snapshotFlow()
            .map { it?.toGlucoseReading() }

    // A count limit, not a date range - a fixed sinceMs computed once at
    // listener-attach time would never advance under SharingStarted.Lazily,
    // so "last 24 hours" would accumulate unbounded history. limit(N) is
    // self-bounding regardless of listener age; callers trim to their
    // actual desired window client-side.
    fun observeRecentReadings(patientId: String, limit: Long): Flow<List<GlucoseReading>> =
        firestore.collection("patients").document(patientId)
            .collection("readings")
            .orderBy("dateMs", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(limit)
            .snapshotFlow()
            .map { snapshot -> snapshot.documents.mapNotNull { it.toGlucoseReading() }.asReversed() }

    // No count limit - cleanupOldAlerts already prunes anything older than
    // 3 days server-side.
    fun observeAlertHistory(patientId: String, uid: String): Flow<List<AlertEvent>> =
        firestore.collection("patients").document(patientId)
            .collection("members").document(uid)
            .collection("alerts")
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .snapshotFlow()
            .map { snapshot -> snapshot.documents.map { it.toAlertEvent() } }

    // Two sequential writes, not one batch - the members/{uid} create rule
    // verifies ownership via get() on the parent patient doc, and Firestore
    // evaluates get() in a batch against state *before* the batch, so a
    // combined batch would never see the patient doc it's creating alongside it.
    suspend fun createPatient(ownerUid: String, displayName: String): String {
        val doc = firestore.collection("patients").document()
        doc.set(
            mapOf(
                "ownerUid" to ownerUid,
                "displayName" to displayName,
                "nightscoutUrl" to "",
                "memberUids" to listOf(ownerUid),
                "createdAt" to System.currentTimeMillis()
            )
        ).await()
        doc.collection("members").document(ownerUid)
            .set(mapOf("role" to "owner", "displayName" to displayName))
            .await()
        return doc.id
    }

    // Self-service: adds the caller as a read-only follower, given only the
    // patientId - same trust model as claimIobSource.
    suspend fun joinPatientAsFollower(patientId: String, displayName: String): String {
        val result = functions.getHttpsCallable("joinPatientAsFollower")
            .call(mapOf("patientId" to patientId, "displayName" to displayName))
            .await()
        @Suppress("UNCHECKED_CAST")
        val data = result.data as? Map<String, Any?> ?: emptyMap()
        return data["displayName"] as? String ?: ""
    }

    // Only ever removes a follower - the backend rejects this for the owner.
    suspend fun leavePatient(patientId: String) {
        functions.getHttpsCallable("leavePatient")
            .call(mapOf("patientId" to patientId))
            .await()
    }

    // .update, not .set - only touches the `thresholds` field on the
    // member's own doc (firestore.rules restricts a non-owner to that one
    // field), leaving role/displayName etc. as they are.
    suspend fun saveThresholds(patientId: String, uid: String, thresholds: Thresholds) {
        firestore.collection("patients").document(patientId)
            .collection("members").document(uid)
            .update(mapOf("thresholds" to thresholds.toMap()))
            .await()
    }

    suspend fun savePatientPhysiology(patientId: String, physiology: PatientPhysiology) {
        firestore.collection("patients").document(patientId)
            .collection("thresholds").document("current")
            .set(physiology.toMap())
            .await()
    }

    /** Calls the `verifyGlurooConnection` Cloud Function so Setup can
     * validate a URL + API secret before it's ever saved anywhere. */
    suspend fun verifyGlurooConnection(nightscoutUrl: String, apiSecret: String): Result<String> = runCatching {
        val result = functions.getHttpsCallable("verifyGlurooConnection")
            .call(mapOf("nightscoutUrl" to nightscoutUrl, "apiSecret" to apiSecret))
            .await()
        @Suppress("UNCHECKED_CAST")
        val data = result.data as? Map<String, Any?> ?: emptyMap()
        val ok = data["ok"] as? Boolean ?: false
        val message = data["message"] as? String ?: ""
        if (!ok) error(message.ifBlank { "Could not connect to Gluroo." })
        message
    }

    suspend fun saveCredentials(patientId: String, nightscoutUrl: String, apiSecret: String) {
        functions.getHttpsCallable("savePatientCredentials")
            .call(mapOf("patientId" to patientId, "nightscoutUrl" to nightscoutUrl, "apiSecret" to apiSecret))
            .await()
    }

    suspend fun pairMcuDevice(patientId: String, deviceId: String) {
        functions.getHttpsCallable("pairMcuDevice")
            .call(mapOf("patientId" to patientId, "deviceId" to deviceId))
            .await()
    }

    // Self-service, no owner approval. Returns displayName so the caller can
    // confirm which patient record this device just linked to.
    suspend fun claimIobSource(patientId: String): String {
        val result = functions.getHttpsCallable("claimIobSource")
            .call(mapOf("patientId" to patientId))
            .await()
        @Suppress("UNCHECKED_CAST")
        val data = result.data as? Map<String, Any?> ?: emptyMap()
        return data["displayName"] as? String ?: ""
    }
}
