package com.aadiinfo.nightwatch.notifications

import com.aadiinfo.nightwatch.NightWatchApplication
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
class NightWatchFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val uid = Firebase.auth.currentUser?.uid ?: return
        val container = (application as NightWatchApplication).container
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
     * ongoing notification instead of interrupting like low/high alerts do. */
    private fun handleReadingStatus(data: Map<String, String>) {
        val sgv = data["sgv"]?.toIntOrNull() ?: return
        val displayName = data["displayName"] ?: "NightWatch"
        val arrow = TrendDirection.fromNightscout(data["direction"]).arrow
        val iob = data["iob"]?.toDoubleOrNull()
        val iobUnreliable = data["iobUnreliable"]?.toBoolean() ?: false

        val title = "$displayName: $sgv mg/dL $arrow"
        val text = if (iob != null) {
            "IOB: ${"%.2f".format(iob)}u" + if (iobUnreliable) " (may be unreliable)" else ""
        } else {
            ""
        }

        NotificationHelper.updateReadingStatus(applicationContext, title, text)
        GlucoseWidgetProvider.updateFromReading(applicationContext, "$sgv mg/dL $arrow", text)
    }
}
