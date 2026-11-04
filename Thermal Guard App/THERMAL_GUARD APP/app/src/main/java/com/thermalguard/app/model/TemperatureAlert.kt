package com.thermalguard.app.model

import com.thermalguard.app.data.ServerClock

/**
 * Temperature alert level used by the Android alarm service.
 */
enum class TemperatureAlertLevel(val rank: Int) {
    NORMAL(0),
    WARNING(1),
    CRITICAL(2);

    companion object {
        fun fromName(value: String?): TemperatureAlertLevel =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: NORMAL
    }
}

data class TemperatureAlertState(
    val level: TemperatureAlertLevel = TemperatureAlertLevel.NORMAL,
    val temperatureC: Double? = null,
    val zoneName: String = "",
    val warningThresholdC: Double = 35.0,
    val criticalThresholdC: Double = 38.0,
    val source: String = "live",
    /** The alarm zone's sensor is offline; the Raspberry Pi is holding the alarm. */
    val sensorOffline: Boolean = false,
    /** No heartbeat from the Raspberry Pi: the current state cannot be confirmed. */
    val controllerLost: Boolean = false
)

/** Raspberry Pi heartbeat freshness, measured on the Firebase server clock. */
object ControllerHealth {
    /** The Pi writes a heartbeat every 5 s; three missed beats = offline. */
    const val OFFLINE_MS = 15_000L
    private const val MAX_FUTURE_SKEW_MS = 300_000L

    fun heartbeatMillis(system: LiveSystem): Long =
        system.controller_last_seen_ms.takeIf { it > 0L } ?: system.sensor_cycle_ms

    fun isOnline(system: LiveSystem, nowMillis: Long = ServerClock.now()): Boolean {
        if (system.controller_status.equals("OFFLINE", ignoreCase = true)) return false
        val heartbeat = heartbeatMillis(system)
        if (heartbeat <= 0L) return false
        val age = nowMillis - heartbeat
        return age <= OFFLINE_MS && age >= -MAX_FUTURE_SKEW_MS
    }
}

/**
 * Decides whether the phone should sound a temperature alarm.
 *
 * The Raspberry Pi is the single source of truth: it confirms CRITICAL over
 * several readings, applies hysteresis before recovering, and keeps a zone's
 * alarm while its sensor is offline (`alarm_state`). The phone used to
 * re-derive the level from raw temperatures, which flapped at a threshold
 * (re-sounding a muted alarm every few seconds) and went silent when the hot
 * zone's sensor dropped out. It now follows the Pi's per-zone alarm state.
 */
object TemperatureAlertEvaluator {

    fun evaluate(
        system: LiveSystem,
        zones: List<ZoneReading>,
        nowMillis: Long = ServerClock.now()
    ): TemperatureAlertState {
        val warning = system.warning_temp_c
        val critical = system.critical_temp_c

        if (!ControllerHealth.isOnline(system, nowMillis)) {
            // Unknown, not "safe": callers keep an active alarm and report lost contact.
            return TemperatureAlertState(
                level = TemperatureAlertLevel.NORMAL,
                warningThresholdC = warning,
                criticalThresholdC = critical,
                source = "controller_offline",
                controllerLost = true
            )
        }

        val top = zones
            .map { zone -> zone to levelOf(zone) }
            .filter { (_, level) -> level != TemperatureAlertLevel.NORMAL }
            .maxWithOrNull(
                compareBy<Pair<ZoneReading, TemperatureAlertLevel>> { it.second.rank }
                    .thenBy { liveTemperature(it.first) ?: Double.NEGATIVE_INFINITY }
            )
            ?: return TemperatureAlertState(
                level = TemperatureAlertLevel.NORMAL,
                warningThresholdC = warning,
                criticalThresholdC = critical
            )

        val (zone, level) = top
        val temperature = liveTemperature(zone)
        return TemperatureAlertState(
            level = level,
            temperatureC = temperature,
            zoneName = zone.name.ifBlank { zone.id },
            warningThresholdC = warning,
            criticalThresholdC = critical,
            source = "live",
            sensorOffline = temperature == null
        )
    }

    /** The Pi's alarm level for a zone: alarm_state (held while offline), else status. */
    fun levelOf(zone: ZoneReading): TemperatureAlertLevel {
        val raw = zone.alarmState.ifBlank { zone.status }.trim().uppercase()
        return when {
            raw.contains("CRITICAL") -> TemperatureAlertLevel.CRITICAL
            raw.contains("WARNING") -> TemperatureAlertLevel.WARNING
            else -> TemperatureAlertLevel.NORMAL
        }
    }

    private fun liveTemperature(zone: ZoneReading): Double? {
        val health = "${zone.status} ${zone.quality}".uppercase()
        if (health.contains("OFFLINE") || health.contains("ERROR") || health.contains("FAILED") ||
            health.contains("RETRY")
        ) return null
        return zone.temperature?.takeIf { it.isFinite() }
    }
}
