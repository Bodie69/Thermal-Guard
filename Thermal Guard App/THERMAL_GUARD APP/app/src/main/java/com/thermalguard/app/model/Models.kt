package com.thermalguard.app.model

data class ZoneReading(
    val id: String = "",
    val name: String = "",
    val temperature: Double? = null,
    val humidity: Double? = null,
    val quality: String = "OFFLINE",
    val status: String = "OFFLINE",
    val error: String? = null,
    val consecutive_errors: Int = 0,
    val total_errors: Int = 0,
    val reconnects: Int = 0,
    val last_seen: Double? = null,
    val last_error_at: Double? = null,
    val timestamp: String = "",
    val lastUpdateMillis: Long = 0L,
    /** Raspberry Pi alarm level for this zone; kept while the sensor is offline. */
    val alarmState: String = ""
)

data class AppUser(
    val displayName: String = "",
    val email: String = "",
    val role: String = "",
    val active: Boolean = true,
    val createdAt: Long = 0L,
    val approvedAt: Long = 0L,
    val approvedBy: String = ""
)

data class StaffRequest(
    val displayName: String = "",
    val email: String = "",
    val status: String = "pending",
    val requestedAt: Long = 0L,
    val reviewedAt: Long = 0L,
    val reviewedBy: String = ""
)

data class AlertItem(
    val key: String = "",
    val sourcePath: String = "alerts",
    val type: String = "",
    val zone: String = "",
    val name: String = "",
    val message: String = "",
    val severity: String = "",
    val temperature: Double? = null,
    val humidity: Double? = null,
    val timestamp: String = "",
    val timestampMillis: Long = 0L
)

data class GsmStatus(
    val enabled: Boolean = false,
    val ready: Boolean = false,
    val registered: Boolean = false,
    val signal: Int = -1,
    val operator: String = "",
    val lastSmsStatus: String = "",
    val lastSmsAt: String = "",
    val error: String = ""
)

data class SmsSettings(
    val enabled: Boolean = false,
    val primary: String = "",
    val secondary: String = "",
    val tertiary: String = "",
    val sendWarning: Boolean = true,
    val sendCritical: Boolean = true,
    val sendRecovery: Boolean = true
)

data class LiveSystem(
    val system_health: String = "",
    val sensor_mode: String = "",
    val online_sensors: Int = 0,
    val total_sensors: Int = 3,
    val warning_temp_c: Double = 35.0,
    val critical_temp_c: Double = 38.0,
    // Raspberry Pi heartbeat. Android uses this instead of trusting stale Firebase values.
    val controller_status: String = "",
    val controller_last_seen_ms: Long = 0L,
    val controller_boot_id: String = "",
    val controller_hostname: String = "",
    val controller_version: String = "",
    val controller_uptime_seconds: Long = 0L,
    // Network addresses published by the Raspberry Pi heartbeat. These are
    // dynamic so Settings never relies on a hard-coded IP address.
    val controller_local_ip: String = "",
    val controller_local_interface: String = "",
    val controller_tailscale_ip: String = "",
    val controller_web_port: Int = 8080,
    val controller_local_url: String = "",
    val controller_tailscale_url: String = "",
    val sensor_cycle_ms: Long = 0L,
    val relay_mode: String = "auto",
    val relay_on: Boolean = false,
    val relay_auto_demand: Boolean = false,
    val relay_available: Boolean = false,
    val relay_error: String = "",
    val relay_last_command_id: String = "",
    val relay_last_command_mode: String = "",
    val relay_last_command_ok: Boolean = false,
    val relay_last_command_at: String = "",
    /** Manual OFF returns to AUTO after this many minutes (Raspberry Pi setting). */
    val relay_off_revert_minutes: Double = 30.0,
    /** Seconds left before a manual OFF returns to AUTO; null when not counting down. */
    val relay_off_remaining_seconds: Long? = null,
    val gsm: GsmStatus = GsmStatus()
)

data class StaffRow(
    val uid: String,
    val displayName: String,
    val email: String,
    val role: String,
    val active: Boolean,
    val requestedAt: Long = 0L,
    val requestStatus: String = ""
)
