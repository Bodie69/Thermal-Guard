package com.thermalguard.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.thermalguard.app.data.ThermalRepository
import com.thermalguard.app.model.TemperatureAlertLevel
import com.thermalguard.app.model.TemperatureAlertState

class ThermalMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        // FCM rotated this phone's token: re-register it (rules reject it if the
        // account is no longer active, so a removed user cannot re-subscribe).
        if (FirebaseAuth.getInstance().currentUser == null) return
        if (!TemperatureAlertService.notificationsEnabled(this)) return
        PushRegistration.save(this, ThermalRepository(), token, force = true)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Signed out on this phone: never alarm (covers pushes still in flight).
        if (FirebaseAuth.getInstance().currentUser == null) return
        // High-priority DATA FCM reaches this service even when the UI task was swiped away.
        // The alarm foreground service then owns playback and survives UI closure.
        if (!TemperatureAlertService.notificationsEnabled(this)) return

        val type = message.data["type"] ?: "SYSTEM"
        val upperType = type.uppercase()
        val isCriticalTemperature = upperType == "CRITICAL_TEMP" || upperType.startsWith("CRITICAL_TEMP_")
        val isWarningTemperature = upperType == "WARNING_TEMP" || upperType.startsWith("WARNING_TEMP_")
        val recovered = upperType == "RECOVERY" || upperType == "TEMPERATURE_RECOVERED" || upperType == "ALERT_CLEARED"

        when {
            isCriticalTemperature || isWarningTemperature -> {
                val level = if (isCriticalTemperature) TemperatureAlertLevel.CRITICAL else TemperatureAlertLevel.WARNING
                TemperatureAlertService.apply(
                    this,
                    TemperatureAlertState(
                        level = level,
                        temperatureC = dataDouble(message, "temperature", "temperatureC", "temp", "temp_c"),
                        // "name" is the zone's display name ("Front"); "zone" is its id ("front").
                        zoneName = message.data["name"]
                            ?: message.data["zone"]
                            ?: message.data["sensor"]
                            ?: "Monitored area",
                        warningThresholdC = dataDouble(message, "warningThreshold", "warning_temp_c", "warning") ?: 35.0,
                        criticalThresholdC = dataDouble(message, "criticalThreshold", "critical_temp_c", "critical") ?: 38.0,
                        source = "fcm"
                    )
                )
                // The foreground service owns the persistent alarm notification,
                // preventing a duplicate notification for the same event.
                return
            }
        }

        // Do not blindly clear on one zone's RECOVERY message. The foreground
        // service evaluates all live zones and stops only when no valid sensor is
        // at WARNING/CRITICAL, preventing another hot zone from being silenced.
        showStandardNotification(message, type, recovered)
    }

    private fun showStandardNotification(message: RemoteMessage, type: String, recovered: Boolean) {
        val offline = type.contains("OFFLINE", true)
        // The Pi marks problems that need someone's attention with severity WARNING/CRITICAL
        // (monitoring stalled, controller task crashed, weak power supply, siren re-armed
        // by a new CRITICAL, sensor offline). Those used to arrive at normal priority.
        val severity = message.data["severity"]?.trim()?.uppercase().orEmpty()
        val urgent = offline || severity == "WARNING" || severity == "CRITICAL"
        val channelId = when {
            offline -> "thermal_guard_offline_v96"
            urgent -> "thermal_guard_system_urgent_v98"
            recovered -> "thermal_guard_sensor_v96"
            else -> "thermal_guard_system_v96"
        }
        val channelName = when {
            offline -> "Sensor Offline Alerts"
            urgent -> "Thermal Guard Problems"
            recovered -> "Sensor Recovery"
            else -> "Thermal Guard System"
        }
        val importance = if (urgent) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channelId, channelName, importance))

        val destination = if (type.contains("SENSOR", true) || offline) "diagnostics" else "alerts"
        val pending = PendingIntent.getActivity(
            this,
            destination.hashCode(),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("destination", destination)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = message.notification?.title
            ?: message.data["title"]
            ?: when {
                offline -> "Sensor Offline"
                recovered -> "Sensor Recovered"
                else -> "Thermal Guard"
            }
        val body = message.notification?.body
            ?: message.data["body"]
            ?: message.data["message"]
            ?: "Thermal Guard notification"

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification_thermal_guard)
            .setColor(ContextCompat.getColor(this, if (urgent) R.color.tg_red else R.color.tg_blue))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (urgent) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        manager.notify((System.currentTimeMillis() and 0x7fffffff).toInt(), notification)
    }

    private fun dataDouble(message: RemoteMessage, vararg keys: String): Double? {
        keys.forEach { key ->
            message.data[key]?.trim()?.toDoubleOrNull()?.let { return it }
        }
        return null
    }
}
