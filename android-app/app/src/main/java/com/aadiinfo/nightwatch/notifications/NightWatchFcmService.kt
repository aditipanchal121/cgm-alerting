package com.aadiinfo.nightwatch.notifications

import com.aadiinfo.nightwatch.NightWatchApplication
import com.aadiinfo.nightwatch.domain.model.Severity
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
        val severity = runCatching { Severity.valueOf(data["severity"] ?: "") }.getOrDefault(Severity.INFO)
        val type = data["type"] ?: "ALERT"
        val text = data["message"] ?: message.notification?.body ?: ""
        val title = message.notification?.title ?: type.replace('_', ' ')

        NotificationHelper.showAlert(applicationContext, title, text, severity)
    }
}
