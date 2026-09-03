package com.aadiinfo.nightwatch.data.repository

import com.aadiinfo.nightwatch.domain.model.AlertEvent
import com.aadiinfo.nightwatch.domain.model.GlucoseReading
import com.aadiinfo.nightwatch.domain.model.Patient
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

    fun observeThresholds(patientId: String): Flow<Thresholds> =
        firestore.collection("patients").document(patientId)
            .collection("thresholds").document("current")
            .snapshotFlow()
            .map { it?.toThresholds() ?: Thresholds() }

    fun observeLatestReading(patientId: String): Flow<GlucoseReading?> =
        firestore.collection("patients").document(patientId)
            .collection("readings")
            .orderBy("fetchedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(1)
            .snapshotFlow()
            .map { snapshot -> snapshot.documents.firstOrNull()?.toGlucoseReading() }

    fun observeReadingsSince(patientId: String, sinceMs: Long): Flow<List<GlucoseReading>> =
        firestore.collection("patients").document(patientId)
            .collection("readings")
            .whereGreaterThanOrEqualTo("dateMs", sinceMs)
            .orderBy("dateMs", com.google.firebase.firestore.Query.Direction.ASCENDING)
            .snapshotFlow()
            .map { snapshot -> snapshot.documents.mapNotNull { it.toGlucoseReading() } }

    fun observeAlertHistory(patientId: String, limit: Long = 50): Flow<List<AlertEvent>> =
        firestore.collection("patients").document(patientId)
            .collection("alerts")
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(limit)
            .snapshotFlow()
            .map { snapshot -> snapshot.documents.map { it.toAlertEvent() } }

    /** Creates the patient doc and bootstraps the creator as its owner.
     *
     * These are two sequential writes rather than one atomic batch on purpose:
     * the members/{uid} create rule verifies ownership via get() on the parent
     * patient doc, and Firestore evaluates get()/exists() calls in a batch's
     * rules against the state *before* the batch - so if both writes were in
     * the same batch, that get() would never see the patient doc being
     * created alongside it, and the whole batch would be denied. */
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

    /** Self-service: the signed-in caller adds themself as a read-only
     * follower on this patient directly, given only the patientId - see
     * claimIobSource for the identical trust model, why there's no separate
     * owner-approval step, and why the patient's displayName is returned. */
    suspend fun joinPatientAsFollower(patientId: String, displayName: String): String {
        val result = functions.getHttpsCallable("joinPatientAsFollower")
            .call(mapOf("patientId" to patientId, "displayName" to displayName))
            .await()
        @Suppress("UNCHECKED_CAST")
        val data = result.data as? Map<String, Any?> ?: emptyMap()
        return data["displayName"] as? String ?: ""
    }

    suspend fun saveThresholds(patientId: String, thresholds: Thresholds) {
        firestore.collection("patients").document(patientId)
            .collection("thresholds").document("current")
            .set(thresholds.toMap())
            .await()
    }

    suspend fun acknowledgeAlert(patientId: String, alertId: String) {
        firestore.collection("patients").document(patientId)
            .collection("alerts").document(alertId)
            .update(
                mapOf(
                    "acknowledged" to true,
                    "acknowledgedAt" to System.currentTimeMillis()
                )
            )
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

    /** Self-service: the signed-in caller becomes the trusted IOB source for
     * this patient directly - no owner approval step. Knowing the patientId
     * is already this app's de facto shared-secret boundary (same trust
     * model as ESP32 device pairing), independent of the members/roles
     * system entirely.
     *
     * Returns the patient's displayName so the caller can show *whose*
     * record this device just linked to - patientId alone is an opaque
     * string nobody can visually verify, and entering the wrong one
     * previously succeeded with no way to notice. */
    suspend fun claimIobSource(patientId: String): String {
        val result = functions.getHttpsCallable("claimIobSource")
            .call(mapOf("patientId" to patientId))
            .await()
        @Suppress("UNCHECKED_CAST")
        val data = result.data as? Map<String, Any?> ?: emptyMap()
        return data["displayName"] as? String ?: ""
    }
}
