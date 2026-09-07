package com.aadiinfo.nightwatch.notifications

import com.aadiinfo.nightwatch.VigilApplication
import com.aadiinfo.nightwatch.domain.model.Severity
import com.aadiinfo.nightwatch.domain.model.TrendDirection
import com.google.firebase.auth.auth
import com.google.firebase.Firebase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives the FCM data messages pollGlucose sends. CRITICAL alerts arrive
 * as high-priority data messages specifically so this fires (and can raise
 * AlarmActivity) even if the app was killed or the phone was locked.
 */
class VigilFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val uid = Firebase.auth.currentUser?.uid ?: return
        val container = (application as VigilApplication).container
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { container.fcmTokenRepository.registerToken(uid, token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data

        if (data["kind"] == "reading") {
            handleReadingStatus(data)
            return
        }

        val severity = runCatching { Severity.valueOf(data["severity"] ?: "") }.getOrDefault(Severity.INFO)
        val type = data["type"] ?: "ALERT"
        val text = data["message"] ?: message.notification?.body ?: ""
        val title = message.notification?.title ?: type.replace('_', ' ')

        NotificationHelper.showAlert(applicationContext, title, text, severity)
    }

    /** Every-poll data message driven by the actual reading (never a
     * predictive alert) - kept separate so it can silently update one
     * ongoing notification instead of interrupting like low/high alerts do.
     * Fires every poll cycle regardless of whether Nightscout had anything
     * to report (see pollOnePatient's sendReadingStatusPush) - sgv is
     * absent entirely when there's no sensor data at all, not just old, so
     * this must still update the notification/widget to say so rather than
     * bailing out and leaving them frozen on whatever they last showed. */
    private fun handleReadingStatus(data: Map<String, String>) {
        val sgv = data["sgv"]?.toIntOrNull()
        val displayName = data["displayName"] ?: "Vigil"
        val arrow = TrendDirection.fromNightscout(data["direction"]).arrow
        val iob = data["iob"]?.toDoubleOrNull()
        val iobUnreliable = data["iobUnreliable"]?.toBoolean() ?: false
        val dateMs = data["dateMs"]?.toLongOrNull()
        val ageMinutes = dateMs?.let { (System.currentTimeMillis() - it) / 60_000 }
        // This message fans out identically to every member regardless of
        // their own per-member alert staleMinutes (see pollOnePatient's
        // sendReadingStatusPush - unlike STALE_DATA alerts, it's one shared
        // broadcast, not personalized), so a fixed cutoff matching the
        // default alert threshold is used here rather than fetching each
        // member's own value in this background handler.
        val isStale = ageMinutes != null && ageMinutes >= STALE_READING_MINUTES

        // Never shown as if it's the current reading once stale or absent -
        // a disconnected/missing CGM sensor should read as "---", not
        // silently keep displaying the last real number.
        val glucoseText = if (sgv != null && !isStale) "$sgv mg/dL $arrow" else "---"
        val title = "$displayName: $glucoseText"
        // Null (not zero) means no source has reported IOB - shown explicitly
        // rather than leaving this blank, so a disconnected/expired pump
        // reads as "not available" instead of looking like the notification
        // failed to update at all.
        val text = if (iob != null) {
            "IOB: ${"%.2f".format(iob)}u" + if (iobUnreliable) " (may be unreliable)" else ""
        } else {
            "IOB not available"
        }

        NotificationHelper.updateReadingStatus(applicationContext, title, text)
        GlucoseWidgetProvider.updateFromReading(applicationContext, glucoseText, text)
    }

    private companion object {
        // Matches Thresholds' default staleMinutes (see Models.kt) - the
        // common case for a family that hasn't customized it.
        const val STALE_READING_MINUTES = 20L
    }
}
