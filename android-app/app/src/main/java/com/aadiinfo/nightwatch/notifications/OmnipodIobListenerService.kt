package com.aadiinfo.nightwatch.notifications

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Reads IOB directly from the Omnipod 5 app's own persistent "Automated
 * Mode" notification, as a more reliable alternative to Gluroo's IOB feed
 * (which has been unreliable - see backend/functions/src/externalIob.ts).
 * Writes to patients/{patientId}/externalIob/current, which
 * firestore.rules only accepts from the UID the patient's owner has
 * designated via setIobSource - this service running and this device's
 * local toggle being on does nothing by itself unless that designation
 * matches the signed-in account.
 *
 * Ships in every install of the app rather than a separate build; it's
 * inert until IobSourceSetupScreen's toggle is turned on locally AND
 * Android's separate "Notification access" permission is granted (that
 * permission can only be granted through system Settings - there's no
 * runtime permission dialog for it).
 */
class OmnipodIobListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        val packageName = sbn.packageName.lowercase()
        if (!packageName.contains("insulet") && !packageName.contains("omnipod")) return

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        val patientId = prefs.getString(KEY_PATIENT_ID, null) ?: return

        val extras = sbn.notification.extras
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: return

        val iob = IOB_REGEX.find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: return

        val nowMs = System.currentTimeMillis()
        // Stored as raw bits via a Long, not a Float - SharedPreferences has
        // no native Double support, and a Float round-trip would lose enough
        // precision to make the dedup check below spuriously see "changed"
        // on a value that hasn't actually moved.
        val lastIob = if (prefs.contains(KEY_LAST_IOB)) {
            Double.fromBits(prefs.getLong(KEY_LAST_IOB, 0L))
        } else {
            null
        }
        val lastWrittenAtMs = prefs.getLong(KEY_LAST_WRITTEN_AT, 0L)

        // Dedupe + throttle: Omnipod may repost this notification more often
        // than IOB actually changes (e.g. just refreshing a timestamp), and
        // writing on every repost would be wasteful. But an unchanged value
        // still needs a periodic "heartbeat" write so its reportedAt doesn't
        // go stale past the backend's freshness window and get ignored.
        val valueChanged = lastIob == null || lastIob != iob
        val heartbeatDue = nowMs - lastWrittenAtMs >= HEARTBEAT_MS
        val minIntervalElapsed = nowMs - lastWrittenAtMs >= MIN_WRITE_INTERVAL_MS
        if (!minIntervalElapsed || (!valueChanged && !heartbeatDue)) return

        val uid = Firebase.auth.currentUser?.uid ?: return

        prefs.edit()
            .putLong(KEY_LAST_IOB, iob.toRawBits())
            .putLong(KEY_LAST_WRITTEN_AT, nowMs)
            .apply()

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                Firebase.firestore
                    .collection("patients").document(patientId)
                    .collection("externalIob").document("current")
                    .set(mapOf("iob" to iob, "reportedAt" to nowMs, "reportedBy" to uid))
                    .await()
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "omnipod_iob_listener_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PATIENT_ID = "patient_id"
        private const val KEY_LAST_IOB = "last_iob"
        private const val KEY_LAST_WRITTEN_AT = "last_written_at"

        private const val MIN_WRITE_INTERVAL_MS = 30_000L
        private const val HEARTBEAT_MS = 4 * 60 * 1000L

        private val IOB_REGEX = Regex("""IOB:\s*([0-9]+\.?[0-9]*)\s*U""", RegexOption.IGNORE_CASE)

        /** Called from IobSourceSetupScreen when the user toggles this
         * device's role on/off and when they set/change the patient ID. */
        fun configure(context: Context, patientId: String?, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_PATIENT_ID, patientId)
                .putBoolean(KEY_ENABLED, enabled)
                .apply()
        }

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun getPatientId(context: Context): String? =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_PATIENT_ID, null)
    }
}
