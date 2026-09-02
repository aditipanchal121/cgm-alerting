package com.aadiinfo.nightwatch.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aadiinfo.nightwatch.MainActivity
import com.aadiinfo.nightwatch.domain.model.Severity

/**
 * WARNING/CRITICAL notification channels. CRITICAL is configured like an
 * alarm-clock app's channel (max importance, alarm-usage sound, DND
 * bypass) because a quiet notification defeats the whole point of
 * nighttime alerting.
 */
object NotificationHelper {
    const val CHANNEL_INFO = "info_alerts"
    const val CHANNEL_WARNING = "warning_alerts"
    const val CHANNEL_CRITICAL = "critical_alerts"
    const val CHANNEL_STATUS = "reading_status"

    /** Fixed ID so each update replaces the same ongoing notification instead
     * of stacking a new one every poll. */
    const val STATUS_NOTIFICATION_ID = 1001

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val info = NotificationChannel(CHANNEL_INFO, "Info", NotificationManager.IMPORTANCE_LOW)

        val status = NotificationChannel(
            CHANNEL_STATUS,
            "Current reading",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            // Public so the reading shows directly on the lock screen without
            // unlocking - this is the actual mechanism behind it (a real
            // system-level Always On Display canvas isn't something a
            // third-party app can draw custom content into on stock Android;
            // a lock-screen-visible persistent notification is the standard
            // way CGM companion apps like this achieve the same glanceable
            // result).
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val warning = NotificationChannel(
            CHANNEL_WARNING,
            "Warnings",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
        }

        val critical = NotificationChannel(
            CHANNEL_CRITICAL,
            "Critical alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(
                Settings.System.DEFAULT_ALARM_ALERT_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }

        manager.createNotificationChannels(listOf(info, warning, critical, status))
    }

    private fun mainActivityPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Ongoing, silent notification showing the latest actual reading - kept
     * separate from the alert channels above so it never buzzes, and always
     * reflects the real reading rather than a predicted/alert value. */
    fun updateReadingStatus(context: Context, title: String, text: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainActivityPendingIntent(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        try {
            NotificationManagerCompat.from(context).notify(STATUS_NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted (API 33+).
        }
    }

    fun showAlert(context: Context, title: String, message: String, severity: Severity) {
        val channelId = when (severity) {
            Severity.CRITICAL -> CHANNEL_CRITICAL
            Severity.WARNING -> CHANNEL_WARNING
            Severity.INFO -> CHANNEL_INFO
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(mainActivityPendingIntent(context))
            .setPriority(
                if (severity == Severity.INFO) NotificationCompat.PRIORITY_LOW
                else NotificationCompat.PRIORITY_HIGH
            )

        if (severity == Severity.CRITICAL) {
            val alarmIntent = Intent(context, AlarmActivity::class.java).apply {
                putExtra(AlarmActivity.EXTRA_TITLE, title)
                putExtra(AlarmActivity.EXTRA_MESSAGE, message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val alarmPendingIntent = PendingIntent.getActivity(
                context,
                0,
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setFullScreenIntent(alarmPendingIntent, true)
            builder.setContentIntent(alarmPendingIntent)
            builder.setCategory(NotificationCompat.CATEGORY_ALARM)
            // Some OEM skins don't reliably honor a full-screen intent from a
            // background-posted notification, so also launch it directly.
            context.startActivity(alarmIntent)
        }

        try {
            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted (API 33+); the full-screen alarm
            // activity above still fires for CRITICAL alerts either way.
        }
    }
}
