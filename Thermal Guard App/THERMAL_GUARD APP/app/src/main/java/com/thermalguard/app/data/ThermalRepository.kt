package com.thermalguard.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.thermalguard.app.model.*
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Single Firebase data gateway used by the Android app.
 *
 * Access model:
 * - First Google sign-in creates a /staff_requests/{uid} request only.
 * - Admin approval creates /users/{uid} with role=staff and active=true.
 * - Active staff can monitor and control the relay.
 * - Admin owns thresholds, SIM800L settings, and staff access management.
 */
class ThermalRepository {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val database: FirebaseDatabase by lazy {
        FirebaseDatabase.getInstance("https://thermalguard-4abbb-default-rtdb.asia-southeast1.firebasedatabase.app")
            .also(ServerClock::start)
    }
    private val root: DatabaseReference by lazy { database.reference.child("thermal_guard") }

    fun uid(): String? = auth.currentUser?.uid

    private fun firebaseError(path: String, error: DatabaseError): String {
        return if (error.code == DatabaseError.PERMISSION_DENIED) {
            "Firebase permission denied at $path. Publish database.rules.json and verify this account's role/active status."
        } else {
            "$path: ${error.message}"
        }
    }

    private fun DataSnapshot.str(path: String, default: String = ""): String {
        val v = child(path).value ?: return default
        return when (v) {
            is String -> v
            is Number, is Boolean -> v.toString()
            else -> default
        }
    }

    private fun DataSnapshot.double(path: String): Double? {
        val v = child(path).value ?: return null
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        }
    }

    private fun DataSnapshot.int(path: String, default: Int = 0): Int {
        val v = child(path).value ?: return default
        return when (v) {
            is Number -> v.toInt()
            is String -> v.trim().toDoubleOrNull()?.toInt() ?: default
            else -> default
        }
    }

    private fun DataSnapshot.long(path: String, default: Long = 0L): Long {
        val v = child(path).value ?: return default
        return when (v) {
            is Number -> v.toLong()
            is String -> v.trim().toDoubleOrNull()?.toLong() ?: default
            else -> default
        }
    }

    private fun DataSnapshot.bool(path: String, default: Boolean = false): Boolean {
        val v = child(path).value ?: return default
        return when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> when (v.trim().lowercase()) {
                "true", "1", "yes", "active", "enabled" -> true
                "false", "0", "no", "inactive", "disabled" -> false
                else -> default
            }
            else -> default
        }
    }

    private fun parseUser(s: DataSnapshot): AppUser? {
        if (!s.exists()) return null
        return AppUser(
            displayName = s.str("displayName", s.str("name")),
            email = s.str("email"),
            role = s.str("role").lowercase(),
            active = s.bool("active", true),
            createdAt = s.long("createdAt"),
            approvedAt = s.long("approvedAt"),
            approvedBy = s.str("approvedBy")
        )
    }

    private fun parseRequest(s: DataSnapshot) = StaffRequest(
        displayName = s.str("displayName", s.str("name")),
        email = s.str("email"),
        status = s.str("status", "pending").lowercase(),
        requestedAt = s.long("requestedAt"),
        reviewedAt = s.long("reviewedAt"),
        reviewedBy = s.str("reviewedBy")
    )

    private fun normalizeEpochMillis(value: Any?): Long {
        val raw = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        } ?: return 0L
        return if (raw in 1.0..9_999_999_999.0) (raw * 1000.0).toLong() else raw.toLong()
    }

    /** clockShiftMs converts Raspberry Pi clock times to Firebase server time. */
    private fun parseZone(s: DataSnapshot, id: String, clockShiftMs: Long = 0L): ZoneReading {
        val fallbackName = when (id) {
            "entrance" -> "Entrance"
            "exit" -> "Exit"
            else -> "Front of ComLab 1"
        }
        return ZoneReading(
            id = s.str("id", id),
            name = s.str("name", fallbackName),
            temperature = s.double("temperature"),
            humidity = s.double("humidity"),
            quality = s.str("quality", "OFFLINE"),
            status = s.str("status", "OFFLINE"),
            error = s.child("error").value?.toString()?.takeIf { it != "null" && it.isNotBlank() },
            consecutive_errors = s.int("consecutive_errors", s.int("critical_confirm_count", 0)),
            total_errors = s.int("total_errors", 0),
            reconnects = s.int("reconnects", 0),
            last_seen = s.double("last_seen"),
            last_error_at = s.double("last_error_at"),
            timestamp = s.child("timestamp").value?.toString() ?: "",
            lastUpdateMillis = normalizeEpochMillis(s.child("last_seen").value ?: s.child("timestamp").value)
                .let { if (it > 0L) it + clockShiftMs else it },
            alarmState = s.str("alarm_state")
        )
    }

    private fun parseAlertTimestampMillis(value: Any?): Long {
        if (value == null) return 0L
        if (value is Number) return normalizeEpochMillis(value)
        val text = value.toString().trim()
        if (text.isBlank()) return 0L
        text.toDoubleOrNull()?.let { return normalizeEpochMillis(it) }
        runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        return 0L
    }

    private fun parseAlert(s: DataSnapshot, sourcePath: String): AlertItem {
        val timestampValue = s.child("timestamp").value ?: s.child("createdAt").value ?: s.child("created_at").value
        return AlertItem(
            key = s.key.orEmpty(),
            sourcePath = sourcePath,
            type = s.str("type"),
            zone = s.str("zone"),
            name = s.str("name"),
            message = s.str("message"),
            severity = s.str("severity"),
            temperature = s.double("temperature"),
            humidity = s.double("humidity"),
            timestamp = timestampValue?.toString() ?: "",
            timestampMillis = parseAlertTimestampMillis(timestampValue)
        )
    }

    fun observeCurrentUser(onChanged: (AppUser?) -> Unit, onError: (String) -> Unit = {}): ValueEventListener? {
        val currentUid = uid() ?: return null
        val ref = root.child("users/$currentUid")
        return ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                runCatching { parseUser(snapshot) }
                    .onSuccess(onChanged)
                    .onFailure { onError("User data error: ${it.message ?: "invalid data"}") }
            }
            override fun onCancelled(error: DatabaseError) = onError(firebaseError("users/$currentUid", error))
        })
    }

    fun removeCurrentUserObserver(listener: ValueEventListener) {
        uid()?.let { root.child("users/$it").removeEventListener(listener) }
    }

    /**
     * Creates the first staff request. Rejected/removed requests are not silently re-opened;
     * the user explicitly taps "Request access again" from the approval screen.
     */
    fun submitPendingStaff(force: Boolean = false, done: (Boolean, String?) -> Unit = { _, _ -> }) {
        val user = auth.currentUser ?: return done(false, "Not signed in")
        val ref = root.child("staff_requests/${user.uid}")
        ref.get().addOnSuccessListener { existing ->
            val currentStatus = existing.str("status").lowercase()
            if (existing.exists() && !force) {
                return@addOnSuccessListener done(true, null)
            }
            if (currentStatus == "approved") {
                return@addOnSuccessListener done(true, null)
            }
            val values = mutableMapOf<String, Any?>(
                "displayName" to (user.displayName ?: ""),
                "email" to (user.email ?: ""),
                "photoUrl" to (user.photoUrl?.toString() ?: ""),
                "status" to "pending",
                "updatedAt" to ServerValue.TIMESTAMP
            )
            if (!existing.exists() || force) values["requestedAt"] = ServerValue.TIMESTAMP
            ref.updateChildren(values).addOnCompleteListener { done(it.isSuccessful, it.exception?.message) }
        }.addOnFailureListener { done(false, it.message) }
    }

    fun observeMyStaffRequest(onChanged: (StaffRequest?) -> Unit, onError: (String) -> Unit = {}): ValueEventListener? {
        val currentUid = uid() ?: return null
        val ref = root.child("staff_requests/$currentUid")
        return ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onChanged(if (snapshot.exists()) parseRequest(snapshot) else null)
            }
            override fun onCancelled(error: DatabaseError) = onError(firebaseError("staff_requests/$currentUid", error))
        })
    }

    fun removeMyStaffRequestObserver(listener: ValueEventListener) {
        uid()?.let { root.child("staff_requests/$it").removeEventListener(listener) }
    }

    fun observeLive(onChanged: (LiveSystem, List<ZoneReading>) -> Unit, onError: (String) -> Unit = {}): ValueEventListener =
        root.child("system/live").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                runCatching {
                    val relayMode = s.str("relay/mode", s.str("relay_mode", "auto")).lowercase()
                    // The Pi stamps controller/server_seen with the Firebase server clock in
                    // the same write as its own last_seen_epoch_ms. Their difference is the
                    // Pi's clock error, used to move every Pi timestamp onto server time so
                    // freshness checks never compare two different clocks.
                    val piSeenMs = normalizeEpochMillis(s.child("controller/last_seen_epoch_ms").value)
                        .takeIf { it > 0L }
                        ?: parseAlertTimestampMillis(s.child("controller/last_seen").value)
                    val serverSeenMs = normalizeEpochMillis(s.child("controller/server_seen").value)
                    val clockShiftMs = if (serverSeenMs > 0L && piSeenMs > 0L) serverSeenMs - piSeenMs else 0L
                    val liveCycleMs = normalizeEpochMillis(s.child("sample_epoch_ms").value)
                        .let { if (it > 0L) it + clockShiftMs else it }
                    val controllerLastSeenMs = serverSeenMs.takeIf { it > 0L }
                        ?: piSeenMs.takeIf { it > 0L }
                        ?: liveCycleMs
                    val system = LiveSystem(
                        system_health = s.str("system_health"),
                        sensor_mode = s.str("sensor_mode"),
                        online_sensors = s.int("online_sensors", 0),
                        total_sensors = s.int("total_sensors", 3),
                        warning_temp_c = s.double("warning_temp_c") ?: 35.0,
                        critical_temp_c = s.double("critical_temp_c") ?: 38.0,
                        controller_status = s.str("controller/status", if (controllerLastSeenMs > 0L) "ONLINE" else ""),
                        controller_last_seen_ms = controllerLastSeenMs,
                        controller_boot_id = s.str("controller/boot_id"),
                        controller_hostname = s.str("controller/hostname"),
                        controller_version = s.str("controller/version", s.str("version")),
                        controller_uptime_seconds = s.long("controller/uptime_seconds", 0L),
                        controller_local_ip = s.str("controller/local_ip"),
                        controller_local_interface = s.str("controller/local_interface"),
                        controller_tailscale_ip = s.str("controller/tailscale_ip"),
                        controller_web_port = s.int("controller/web_port", 8080),
                        controller_local_url = s.str("controller/local_url"),
                        controller_tailscale_url = s.str("controller/tailscale_url"),
                        sensor_cycle_ms = liveCycleMs,
                        relay_mode = relayMode,
                        relay_on = s.bool("relay/on", false),
                        relay_auto_demand = s.bool("relay/auto_demand", false),
                        relay_available = s.bool("relay/available", false),
                        relay_error = s.str("relay/error"),
                        relay_last_command_id = s.str("relay/last_command_id"),
                        relay_last_command_mode = s.str("relay/last_command_mode"),
                        relay_last_command_ok = s.bool("relay/last_command_ok", false),
                        relay_last_command_at = s.str("relay/last_command_at"),
                        relay_off_revert_minutes = s.double("relay/off_revert_minutes") ?: 30.0,
                        relay_off_remaining_seconds = (s.child("relay/off_remaining_seconds").value as? Number)?.toLong(),
                        gsm = GsmStatus(
                            enabled = s.bool("gsm/enabled", false),
                            ready = s.bool("gsm/ready", false),
                            registered = s.bool("gsm/registered", false),
                            signal = s.int("gsm/signal", -1),
                            operator = s.str("gsm/operator"),
                            lastSmsStatus = s.str("gsm/last_sms_status"),
                            lastSmsAt = s.str("gsm/last_sms_at"),
                            error = s.str("gsm/error")
                        )
                    )
                    val zonesRoot = s.child("zones")
                    val zones = listOf("entrance", "exit", "front").map { id ->
                        parseZone(zonesRoot.child(id), id, clockShiftMs)
                    }
                    system to zones
                }.onSuccess { onChanged(it.first, it.second) }
                    .onFailure { onError("Live data error: ${it.message ?: "invalid sensor data"}") }
            }
            override fun onCancelled(e: DatabaseError) = onError(firebaseError("system/live", e))
        })

    fun removeLiveObserver(listener: ValueEventListener) {
        root.child("system/live").removeEventListener(listener)
    }

    data class AlertObserverHandle(
        val alertsQuery: Query,
        val alertsListener: ValueEventListener,
        val systemAlertsQuery: Query,
        val systemAlertsListener: ValueEventListener
    )

    fun observeAlerts(onChanged: (List<AlertItem>) -> Unit, onError: (String) -> Unit = {}): AlertObserverHandle {
        val combined = linkedMapOf<String, AlertItem>()
        fun emit() = onChanged(
            combined.values
                .sortedWith(compareByDescending<AlertItem> { it.timestampMillis }.thenByDescending { it.timestamp })
                .take(100)
        )

        val alertsQuery = root.child("alerts").limitToLast(50)
        val alertsListener = alertsQuery.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                runCatching {
                    combined.keys.filter { it.startsWith("alerts:") }.toList().forEach(combined::remove)
                    s.children.forEach { c -> combined["alerts:${c.key}"] = parseAlert(c, "alerts") }
                    emit()
                }.onFailure { onError("Alert data error: ${it.message ?: "invalid alert"}") }
            }
            override fun onCancelled(e: DatabaseError) = onError(firebaseError("alerts", e))
        })

        val systemAlertsQuery = root.child("system_alerts").limitToLast(50)
        val systemAlertsListener = systemAlertsQuery.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                runCatching {
                    combined.keys.filter { it.startsWith("system_alerts:") }.toList().forEach(combined::remove)
                    s.children.forEach { c -> combined["system_alerts:${c.key}"] = parseAlert(c, "system_alerts") }
                    emit()
                }.onFailure { onError("System alert data error: ${it.message ?: "invalid alert"}") }
            }
            override fun onCancelled(e: DatabaseError) = onError(firebaseError("system_alerts", e))
        })

        return AlertObserverHandle(alertsQuery, alertsListener, systemAlertsQuery, systemAlertsListener)
    }

    fun removeAlertsObserver(handle: AlertObserverHandle) {
        handle.alertsQuery.removeEventListener(handle.alertsListener)
        handle.systemAlertsQuery.removeEventListener(handle.systemAlertsListener)
    }

    fun deleteAlert(alert: AlertItem, done: (Boolean, String?) -> Unit = { _, _ -> }) {
        if (alert.key.isBlank() || alert.sourcePath !in setOf("alerts", "system_alerts")) {
            done(false, "Alert has no valid Firebase key")
            return
        }
        root.child(alert.sourcePath).child(alert.key).removeValue()
            .addOnCompleteListener { done(it.isSuccessful, it.exception?.message) }
    }

    fun deleteAlerts(alerts: Collection<AlertItem>, done: (Boolean, String?) -> Unit = { _, _ -> }) {
        val updates = linkedMapOf<String, Any?>()
        alerts.forEach { alert ->
            if (alert.key.isNotBlank() && alert.sourcePath in setOf("alerts", "system_alerts")) {
                updates["${alert.sourcePath}/${alert.key}"] = null
            }
        }
        if (updates.isEmpty()) {
            done(true, null)
            return
        }
        root.updateChildren(updates).addOnCompleteListener { done(it.isSuccessful, it.exception?.message) }
    }

    fun observeStaffRequests(onChanged: (Map<String, StaffRequest>) -> Unit, onError: (String) -> Unit = {}) =
        root.child("staff_requests").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                runCatching {
                    val result = linkedMapOf<String, StaffRequest>()
                    s.children.forEach { c -> c.key?.let { result[it] = parseRequest(c) } }
                    result
                }.onSuccess(onChanged).onFailure { onError("Staff request data error: ${it.message}") }
            }
            override fun onCancelled(e: DatabaseError) = onError(firebaseError("staff_requests", e))
        })

    fun observeUsers(onChanged: (Map<String, AppUser>) -> Unit, onError: (String) -> Unit = {}) =
        root.child("users").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                runCatching {
                    val result = linkedMapOf<String, AppUser>()
                    s.children.forEach { c -> c.key?.let { key -> parseUser(c)?.let { result[key] = it } } }
                    result
                }.onSuccess(onChanged).onFailure { onError("User list data error: ${it.message}") }
            }
            override fun onCancelled(e: DatabaseError) = onError(firebaseError("users", e))
        })

    fun removeStaffRequestsObserver(listener: ValueEventListener) {
        root.child("staff_requests").removeEventListener(listener)
    }

    fun removeUsersObserver(listener: ValueEventListener) {
        root.child("users").removeEventListener(listener)
    }

    fun approveStaff(uid: String, request: StaffRequest, done: (Boolean, String?) -> Unit) {
        val actor = this.uid() ?: return done(false, "Not signed in")
        val now = ServerValue.TIMESTAMP
        val updates = hashMapOf<String, Any?>(
            "users/$uid/displayName" to request.displayName,
            "users/$uid/email" to request.email,
            "users/$uid/role" to "staff",
            "users/$uid/active" to true,
            "users/$uid/createdAt" to now,
            "users/$uid/approvedAt" to now,
            "users/$uid/approvedBy" to actor,
            "staff_requests/$uid/status" to "approved",
            "staff_requests/$uid/reviewedAt" to now,
            "staff_requests/$uid/reviewedBy" to actor
        )
        root.updateChildren(updates).addOnCompleteListener { done(it.isSuccessful, it.exception?.message) }
    }

    fun rejectStaff(uid: String, done: (Boolean, String?) -> Unit) {
        val actor = this.uid() ?: return done(false, "Not signed in")
        root.child("staff_requests/$uid").updateChildren(
            mapOf(
                "status" to "rejected",
                "reviewedAt" to ServerValue.TIMESTAMP,
                "reviewedBy" to actor
            )
        ).addOnCompleteListener { done(it.isSuccessful, it.exception?.message) }
    }

    fun updateStaffProfile(uid: String, displayName: String, done: (Boolean, String?) -> Unit = { _, _ -> }) {
        val clean = displayName.trim()
        if (clean.length < 2) return done(false, "Enter a valid staff name")
        root.child("users/$uid").updateChildren(
            mapOf("displayName" to clean, "updatedAt" to ServerValue.TIMESTAMP)
        ).addOnCompleteListener { done(it.isSuccessful, it.exception?.message) }
    }

    fun setUserActive(uid: String, active: Boolean, done: (Boolean, String?) -> Unit = { _, _ -> }) {
        root.child("users/$uid").updateChildren(
            mapOf("active" to active, "updatedAt" to ServerValue.TIMESTAMP)
        ).addOnCompleteListener { done(it.isSuccessful, it.exception?.message) }
    }

    /** Removes app access only. It does not delete the person's Google/Firebase Auth identity. */
    fun removeStaffAccess(uid: String, done: (Boolean, String?) -> Unit = { _, _ -> }) {
        val actor = this.uid() ?: return done(false, "Not signed in")
        val updates = hashMapOf<String, Any?>(
            "users/$uid" to null,
            "staff_requests/$uid/status" to "removed",
            "staff_requests/$uid/reviewedAt" to ServerValue.TIMESTAMP,
            "staff_requests/$uid/reviewedBy" to actor
        )
        root.updateChildren(updates).addOnCompleteListener { done(it.isSuccessful, it.exception?.message) }
    }

    fun setSirenMode(
        mode: String,
        done: (Boolean, String?, String?) -> Unit = { _, _, _ -> }
    ) {
        val actor = uid() ?: return done(false, "Not signed in", null)
        if (mode !in setOf("auto", "on", "off")) return done(false, "Invalid siren mode", null)
        val requestId = UUID.randomUUID().toString()
        root.child("commands/relay").updateChildren(
            mapOf(
                "mode" to mode,
                "requestedBy" to actor,
                "requestedAt" to ServerValue.TIMESTAMP,
                "requestId" to requestId
            )
        ).addOnCompleteListener {
            done(it.isSuccessful, it.exception?.message, if (it.isSuccessful) requestId else null)
        }
    }

    // Backward-compatible wrapper used by any older screen/code path.
    fun setRelay(mode: String, done: (Boolean, String?) -> Unit = { _, _ -> }) {
        setSirenMode(mode) { ok, error, _ -> done(ok, error) }
    }

    fun observeSmsSettings(onChanged: (SmsSettings) -> Unit, onError: (String) -> Unit = {}): ValueEventListener =
        root.child("settings/sms").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                runCatching {
                    SmsSettings(
                        enabled = s.bool("enabled", false),
                        primary = s.str("recipients/primary"),
                        secondary = s.str("recipients/secondary"),
                        tertiary = s.str("recipients/tertiary"),
                        sendWarning = s.bool("send_warning", true),
                        sendCritical = s.bool("send_critical", true),
                        sendRecovery = s.bool("send_recovery", true)
                    )
                }.onSuccess(onChanged)
                    .onFailure { onError("SMS settings error: ${it.message ?: "invalid data"}") }
            }
            override fun onCancelled(error: DatabaseError) = onError(firebaseError("settings/sms", error))
        })

    fun removeSmsSettingsObserver(listener: ValueEventListener) {
        root.child("settings/sms").removeEventListener(listener)
    }

    fun saveSmsSettings(settings: SmsSettings, done: (Boolean, String?) -> Unit = { _, _ -> }) {
        val recipients = mapOf(
            "primary" to settings.primary,
            "secondary" to settings.secondary,
            "tertiary" to settings.tertiary
        )
        root.child("settings/sms").setValue(
            mapOf(
                "enabled" to settings.enabled,
                "recipients" to recipients,
                "send_warning" to settings.sendWarning,
                "send_critical" to settings.sendCritical,
                "send_recovery" to settings.sendRecovery,
                "updatedBy" to (uid() ?: ""),
                "updatedAt" to ServerValue.TIMESTAMP
            )
        ).addOnCompleteListener { done(it.isSuccessful, it.exception?.message) }
    }

    fun requestTestSms(recipient: String, done: (Boolean, String?) -> Unit = { _, _ -> }) {
        val actor = uid() ?: return done(false, "Not signed in")
        val requestId = java.util.UUID.randomUUID().toString()
        root.child("commands/gsm/test").setValue(
            mapOf(
                "requestId" to requestId,
                "recipient" to recipient,
                "message" to "Thermal Guard test SMS from RSU Cajidiocan ComLab 1.",
                "requestedBy" to actor,
                "requestedAt" to ServerValue.TIMESTAMP,
                "status" to "PENDING"
            )
        ).addOnCompleteListener { done(it.isSuccessful, it.exception?.message) }
    }

    /** Stable, path-safe id for an FCM token (tokens contain ':' and are long). */
    private fun tokenId(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)

    /** Register this phone for alarm pushes (the Pi sends only to active users' tokens). */
    fun saveFcmToken(token: String, done: (Boolean, String?) -> Unit = { _, _ -> }) {
        val actor = uid() ?: return done(false, "Not signed in")
        root.child("users/$actor/fcm_tokens/${tokenId(token)}").setValue(
            mapOf("token" to token, "updatedAt" to ServerValue.TIMESTAMP, "platform" to "android")
        ).addOnCompleteListener { done(it.isSuccessful, it.exception?.message) }
    }

    fun deleteFcmToken(token: String, done: (Boolean, String?) -> Unit = { _, _ -> }) {
        val actor = uid() ?: return done(false, "Not signed in")
        root.child("users/$actor/fcm_tokens/${tokenId(token)}").removeValue()
            .addOnCompleteListener { done(it.isSuccessful, it.exception?.message) }
    }

    fun setThresholds(warning: Double, critical: Double, done: (Boolean, String?) -> Unit) {
        if (warning < 0 || critical > 80 || warning >= critical) return done(false, "Warning must be lower than critical.")
        root.child("settings").updateChildren(
            mapOf("warning_temp_c" to warning, "critical_temp_c" to critical)
        ).addOnCompleteListener { done(it.isSuccessful, it.exception?.message) }
    }
}
