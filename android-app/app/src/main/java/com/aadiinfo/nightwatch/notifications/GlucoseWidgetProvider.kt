package com.aadiinfo.nightwatch.notifications

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.aadiinfo.nightwatch.MainActivity
import com.aadiinfo.nightwatch.R

/**
 * Home-screen widget showing the latest reading, trend arrow, and IOB - a
 * standard, fully-supported Android feature, unlike a real system Always On
 * Display canvas, which a third-party app can't draw custom content into.
 *
 * Kept current by [updateFromReading], called from [VigilFcmService]
 * every time a "reading" data message arrives (the same trigger that
 * updates the persistent status notification) - not by the OS's own widget
 * update schedule, which the platform clamps to a 30-minute floor
 * regardless of what's configured in glucose_widget_info.xml. That
 * manifest-declared period is kept only as an infrequent safety net so an
 * already-placed widget still redraws periodically if a push is missed.
 */
class GlucoseWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val reading = prefs.getString(KEY_READING, null)
            ?: context.getString(R.string.widget_placeholder_reading)
        val iobText = prefs.getString(KEY_IOB_TEXT, "") ?: ""
        appWidgetIds.forEach { id -> updateWidget(context, appWidgetManager, id, reading, iobText) }
    }

    companion object {
        private const val PREFS_NAME = "glucose_widget_prefs"
        private const val KEY_READING = "reading"
        private const val KEY_IOB_TEXT = "iob_text"

        /** Pushes the latest reading to every placed instance of this widget
         * and caches it, so a freshly-added widget (or one redrawn by the
         * OS's own periodic update) has something real to show immediately
         * instead of the placeholder. [reading] is a compact "123 mg/dL ↑"
         * string - deliberately without the patient's display name prefix
         * the notification uses, since a home-screen widget has much less
         * room and is already implicitly "this household's" reading. */
        fun updateFromReading(context: Context, reading: String, iobText: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_READING, reading)
                .putString(KEY_IOB_TEXT, iobText)
                .apply()

            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, GlucoseWidgetProvider::class.java))
            ids.forEach { id -> updateWidget(context, manager, id, reading, iobText) }
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            reading: String,
            iobText: String
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_glucose)
            views.setTextViewText(R.id.widget_reading, reading)
            views.setTextViewText(R.id.widget_iob, iobText)

            val openAppIntent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_reading, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
