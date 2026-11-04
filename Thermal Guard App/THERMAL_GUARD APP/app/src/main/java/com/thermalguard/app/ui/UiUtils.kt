package com.thermalguard.app.ui

import com.thermalguard.app.data.ServerClock
import android.content.Context
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.thermalguard.app.R
import com.thermalguard.app.model.ControllerHealth
import com.thermalguard.app.model.LiveSystem
import com.thermalguard.app.model.ZoneReading
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** One label/value line on a card. See item_detail_row.xml. */
data class DetailRow(val label: String, val value: String, val valueColor: Int? = null)

object UiUtils {

    /** Sensor sample must be newer than this to be shown as live. */
    const val LIVE_SENSOR_STALE_MS = 30_000L

    /**
     * Raspberry Pi publishes a heartbeat every 5 seconds. Three missed beats
     * means the controller is treated as offline even if Firebase still holds
     * the previous ONLINE/sensor values.
     */
    const val CONTROLLER_OFFLINE_MS = ControllerHealth.OFFLINE_MS

    fun controllerHeartbeatMillis(system: LiveSystem): Long = ControllerHealth.heartbeatMillis(system)

    fun controllerAgeMs(system: LiveSystem, nowMillis: Long = ServerClock.now()): Long {
        val heartbeat = controllerHeartbeatMillis(system)
        if (heartbeat <= 0L) return Long.MAX_VALUE
        return (nowMillis - heartbeat).coerceAtLeast(0L)
    }

    /** Same rule the alarm uses (Firebase server time), so the two never disagree. */
    fun isControllerOnline(system: LiveSystem, nowMillis: Long = ServerClock.now()): Boolean =
        ControllerHealth.isOnline(system, nowMillis)

    fun controllerStatus(system: LiveSystem, nowMillis: Long = ServerClock.now()): String =
        if (isControllerOnline(system, nowMillis)) "ONLINE" else "OFFLINE"

    fun controllerAgeText(system: LiveSystem, nowMillis: Long = ServerClock.now()): String {
        val heartbeat = controllerHeartbeatMillis(system)
        if (heartbeat <= 0L) return "heartbeat unavailable"
        val seconds = controllerAgeMs(system, nowMillis) / 1000L
        return when {
            seconds < 2 -> "heartbeat now"
            seconds < 60 -> "heartbeat ${seconds}s ago"
            seconds < 3600 -> "heartbeat ${seconds / 60}m ago"
            else -> "heartbeat ${seconds / 3600}h ago"
        }
    }

    /** Shared live-reading rules used by Dashboard and Health/Diagnostics. */
    fun zoneAgeMs(zone: ZoneReading, nowMillis: Long = ServerClock.now()): Long =
        if (zone.lastUpdateMillis > 0L) (nowMillis - zone.lastUpdateMillis).coerceAtLeast(0L) else Long.MAX_VALUE

    fun isZoneStale(zone: ZoneReading, nowMillis: Long = ServerClock.now()): Boolean =
        zoneAgeMs(zone, nowMillis) > LIVE_SENSOR_STALE_MS

    fun isZoneOffline(zone: ZoneReading): Boolean {
        val health = "${zone.status} ${zone.quality}".uppercase(Locale.US)
        return health.contains("OFFLINE") || health.contains("ERROR") || health.contains("FAILED")
    }

    fun effectiveZoneStatus(
        zone: ZoneReading,
        controllerOnline: Boolean,
        nowMillis: Long = ServerClock.now()
    ): String = when {
        !controllerOnline -> "OFFLINE"
        isZoneStale(zone, nowMillis) -> "OFFLINE"
        isZoneOffline(zone) -> "OFFLINE"
        else -> zone.status.ifBlank { zone.quality.ifBlank { "ONLINE" } }
    }

    // Backward-compatible helper for code paths that only care about sensor freshness.
    fun effectiveZoneStatus(zone: ZoneReading, nowMillis: Long = ServerClock.now()): String =
        effectiveZoneStatus(zone, controllerOnline = true, nowMillis = nowMillis)

    fun zoneTemperatureText(
        zone: ZoneReading,
        controllerOnline: Boolean,
        nowMillis: Long = ServerClock.now()
    ): String {
        if (effectiveZoneStatus(zone, controllerOnline, nowMillis).equals("OFFLINE", true)) return "--.-- °C"
        return zone.temperature?.let { String.format(Locale.US, "%.2f °C", it) } ?: "--.-- °C"
    }

    fun zoneTemperatureText(zone: ZoneReading, nowMillis: Long = ServerClock.now()): String =
        zoneTemperatureText(zone, controllerOnline = true, nowMillis = nowMillis)

    fun zoneHumidityText(
        zone: ZoneReading,
        controllerOnline: Boolean,
        nowMillis: Long = ServerClock.now()
    ): String {
        if (effectiveZoneStatus(zone, controllerOnline, nowMillis).equals("OFFLINE", true)) return "--.-- %"
        return zone.humidity?.let { String.format(Locale.US, "%.2f %%", it) } ?: "--.-- %"
    }

    fun zoneHumidityText(zone: ZoneReading, nowMillis: Long = ServerClock.now()): String =
        zoneHumidityText(zone, controllerOnline = true, nowMillis = nowMillis)

    fun effectiveOnlineCount(
        zones: List<ZoneReading>,
        controllerOnline: Boolean,
        nowMillis: Long = ServerClock.now()
    ): Int {
        if (!controllerOnline) return 0
        return zones.count { z ->
            !effectiveZoneStatus(z, true, nowMillis).equals("OFFLINE", true) && z.temperature != null
        }
    }

    fun statusColor(context: Context, status: String): Int = ContextCompat.getColor(
        context,
        when (status.uppercase()) {
            "NORMAL", "ONLINE", "GOOD", "HEALTHY" -> R.color.tg_green
            "WARNING", "RETRY", "RECOVERING", "SYNCING", "DEGRADED" -> R.color.tg_amber
            "CRITICAL", "OFFLINE", "ERROR", "ALL_SENSORS_OFFLINE" -> R.color.tg_red
            else -> R.color.tg_gray_state
        }
    )

    fun statusBackground(context: Context, status: String): Int = ContextCompat.getColor(
        context,
        when (status.uppercase()) {
            "NORMAL", "ONLINE", "GOOD", "HEALTHY" -> R.color.tg_pill_green_bg
            "WARNING", "RETRY", "RECOVERING", "SYNCING", "DEGRADED" -> R.color.tg_pill_amber_bg
            "CRITICAL", "OFFLINE", "ERROR", "ALL_SENSORS_OFFLINE" -> R.color.tg_pill_red_bg
            else -> R.color.tg_surface
        }
    )

    fun styleChip(chip: Chip, status: String) {
        chip.setTextColor(statusColor(chip.context, status))
        chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(statusBackground(chip.context, status))
        chip.text = status.uppercase()
    }

    /** Fills [container] with aligned rows, reusing views because cards rebind every second. */
    fun bindDetails(container: LinearLayout, rows: List<DetailRow>) {
        val context = container.context
        val inflater = LayoutInflater.from(context)
        while (container.childCount < rows.size) {
            container.addView(inflater.inflate(R.layout.item_detail_row, container, false))
        }
        for (index in 0 until container.childCount) {
            val view = container.getChildAt(index)
            val row = rows.getOrNull(index)
            view.visibility = if (row == null) View.GONE else View.VISIBLE
            if (row == null) continue
            view.findViewById<TextView>(R.id.tvLabel).text = row.label
            view.findViewById<TextView>(R.id.tvValue).apply {
                text = row.value
                setTextColor(row.valueColor ?: ContextCompat.getColor(context, R.color.tg_text))
            }
        }
    }

    private val fullTimeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, yyyy h:mm:ss a", Locale.US).withZone(ZoneId.systemDefault())

    fun fullTime(epochMillis: Long): String =
        if (epochMillis <= 0L) "--" else fullTimeFormat.format(Instant.ofEpochMilli(epochMillis))

    fun fullTime(iso: String): String {
        if (iso.isBlank()) return "--"
        return try {
            fullTimeFormat.format(OffsetDateTime.parse(iso))
        } catch (_: Exception) {
            iso
        }
    }

    fun ageText(ms: Long): String {
        if (ms == Long.MAX_VALUE) return "--"
        val seconds = (ms / 1000L).coerceAtLeast(0L)
        return when {
            seconds < 2 -> "Just now"
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86_400}d ago"
        }
    }

    fun uptimeText(totalSeconds: Long): String {
        if (totalSeconds <= 0L) return "--"
        val days = totalSeconds / 86_400
        val hours = totalSeconds % 86_400 / 3600
        val minutes = totalSeconds % 3600 / 60
        return when {
            days > 0 -> "${days}d ${hours}h ${minutes}m"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    /** The siren lines, shared so Dashboard and Settings always agree. */
    fun sirenRows(system: LiveSystem, online: Boolean): List<DetailRow> {
        val output = when {
            !online -> "Unknown"
            !system.relay_available -> "Unavailable"
            system.relay_on -> "ON"
            else -> "OFF"
        }
        return listOf(
            DetailRow("Control mode", if (online) SirenConfirm.modeText(system) else "Unknown"),
            DetailRow("Relay output", output),
            DetailRow("Auto demand", if (!online) "Unknown" else if (system.relay_auto_demand) "Requested" else "None"),
            DetailRow("Last command", system.relay_last_command_mode.uppercase().ifBlank { "None" }),
            DetailRow("Command time", fullTime(system.relay_last_command_at))
        )
    }

    /** Distance from the next alert limit, e.g. "3.62 °C below warning". */
    fun marginText(temperature: Double?, warning: Double, critical: Double): String {
        if (temperature == null) return "--"
        return when {
            temperature < warning -> String.format(Locale.US, "%.2f °C below warning", warning - temperature)
            temperature < critical -> String.format(Locale.US, "%.2f °C below critical", critical - temperature)
            else -> String.format(Locale.US, "%.2f °C above critical", temperature - critical)
        }
    }

    /** SIM800L AT+CSQ: 0-31 is a real reading; 99 means unknown. */
    fun signalText(csq: Int): String = when (csq) {
        in 0..9 -> "Weak (CSQ $csq/31)"
        in 10..14 -> "Fair (CSQ $csq/31)"
        in 15..19 -> "Good (CSQ $csq/31)"
        in 20..31 -> "Excellent (CSQ $csq/31)"
        else -> "--"
    }

    fun shortTime(iso: String): String {
        if (iso.isBlank()) return "--"
        return try {
            val instant = Instant.parse(iso)
            DateTimeFormatter.ofPattern("h:mm:ss a").withZone(ZoneId.systemDefault()).format(instant)
        } catch (_: Exception) { iso.takeLast(12) }
    }

    fun epochSecondsTime(context: Context, epochSeconds: Double?): String {
        if (epochSeconds == null || epochSeconds <= 0) return "--"
        return DateFormat.getTimeFormat(context).format(Date((epochSeconds * 1000).toLong()))
    }

    fun requestTime(context: Context, epochMillis: Long): String {
        if (epochMillis <= 0) return "Requested recently"
        return DateFormat.getMediumDateFormat(context).format(Date(epochMillis)) + " " +
            DateFormat.getTimeFormat(context).format(Date(epochMillis))
    }
}
