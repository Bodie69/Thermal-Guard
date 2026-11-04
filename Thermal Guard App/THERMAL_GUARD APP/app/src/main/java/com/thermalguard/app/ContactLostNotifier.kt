package com.thermalguard.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * "Thermal Guard stopped reporting" warning.
 *
 * Shown when the Raspberry Pi heartbeat has been missing for a while and no
 * temperature alarm is active (an active alarm shows LOST CONTACT itself).
 * A dead Pi cannot send a push message, so this is the only way people learn
 * that monitoring has stopped - e.g. a power cut or the Pi failing in a fire.
 */
object ContactLostNotifier {
    private const val CHANNEL_ID = "thermal_guard_contact_lost_v98"
    private const val NOTIFICATION_ID = 9610

    fun show(context: Context, offlineSeconds: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Monitoring lost", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Thermal Guard Raspberry Pi stopped reporting"
                enableVibration(true)
            }
        )
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("destination", "diagnostics")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val minutes = (offlineSeconds / 60).coerceAtLeast(1)
        val body = "No report from the Raspberry Pi for $minutes min. Temperatures are not being " +
            "monitored by the app until it reconnects. Check its power and network."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_thermal_guard)
            .setColor(ContextCompat.getColor(context, R.color.tg_red))
            .setContentTitle("Thermal Guard monitoring lost")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
