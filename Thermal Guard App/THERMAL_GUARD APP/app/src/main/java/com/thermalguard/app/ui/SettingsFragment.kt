package com.thermalguard.app.ui

import com.thermalguard.app.data.ServerClock
import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.Lifecycle
import androidx.core.widget.doAfterTextChanged
import com.google.firebase.database.ValueEventListener
import com.thermalguard.app.BuildConfig
import com.thermalguard.app.MainActivity
import com.thermalguard.app.R
import com.thermalguard.app.data.ThermalRepository
import com.thermalguard.app.databinding.FragmentSettingsBinding
import com.thermalguard.app.model.AppUser
import com.thermalguard.app.model.LiveSystem
import com.thermalguard.app.model.SmsSettings
import com.thermalguard.app.model.ZoneReading

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val repo = ThermalRepository()
    private var liveListener: ValueEventListener? = null
    private var smsListener: ValueEventListener? = null
    private var suppressSirenEvents = false
    private var suppressSmsRealtimeEvents = false
    private var smsRealtimeDirty = false
    private var initialized = false
    private var profileRetryCount = 0
    private var latestSystem: LiveSystem? = null
    private var canControlRelayRole = false
    private val handler = Handler(Looper.getMainLooper())

    private val smsRealtimeSave = Runnable {
        if (_binding == null || !initialized || suppressSmsRealtimeEvents || !smsRealtimeDirty) return@Runnable
        saveSmsSettingsRealtime()
    }

    private var latestZones: List<ZoneReading> = emptyList()

    private val controllerWatchdog = object : Runnable {
        override fun run() {
            if (_binding == null || !initialized) return
            renderControllerAvailability()
            renderNetworkAccess()
            handler.postDelayed(this, CONTROLLER_WATCHDOG_MS)
        }
    }

    private val profileRetry = object : Runnable {
        override fun run() {
            val b = _binding ?: return
            val host = activity as? MainActivity ?: return
            val profile = host.profile
            if (profile?.active == true) {
                initializeScreen(host, profile)
                return
            }
            profileRetryCount++
            if (profileRetryCount < PROFILE_RETRY_LIMIT) {
                handler.postDelayed(this, PROFILE_RETRY_MS)
            } else {
                b.tvAccountName.text = "Account data unavailable"
                b.tvAccountEmail.text = "Please check your connection and try again."
                b.tvAccessSummary.text = "Settings are temporarily read-only while the account profile is unavailable."
                setControlsEnabled(false)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        state: Bundle?
    ): View = FragmentSettingsBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, state: Bundle?) {
        val b = _binding ?: return
        b.tvAccountName.text = "Loading account…"
        b.tvAccountEmail.text = ""
        b.tvAccessSummary.text = "Preparing settings…"
        setControlsEnabled(false)
        // Network addresses are read-only, but start disabled until a fresh
        // controller heartbeat is received.
        renderNetworkAccess()

        // Theme selection is a local UI preference and must remain available even when
        // Firebase/profile data is still loading or temporarily offline.
        val uiPrefs = b.root.context.getSharedPreferences("thermal_guard_ui", 0)
        b.switchDark.isChecked = uiPrefs.getBoolean("dark_mode", true)
        b.switchDark.isEnabled = true
        b.switchDark.setOnCheckedChangeListener { _, checked ->
            if (_binding == null) return@setOnCheckedChangeListener
            val current = uiPrefs.getBoolean("dark_mode", true)
            if (current == checked) return@setOnCheckedChangeListener
            uiPrefs.edit().putBoolean("dark_mode", checked).apply()
            AppCompatDelegate.setDefaultNightMode(
                if (checked) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        val host = activity as? MainActivity ?: return
        val profile = host.profile
        if (profile?.active == true) {
            initializeScreen(host, profile)
        } else {
            // Do not re-route or replace the current Fragment during a cold-start profile refresh.
            // Wait briefly for MainActivity's Firebase profile observer instead.
            handler.post(profileRetry)
        }
    }

    private fun initializeScreen(host: MainActivity, profile: AppUser) {
        if (initialized || _binding == null || !profile.active) return
        initialized = true
        handler.removeCallbacks(profileRetry)

        val b = _binding ?: return
        val role = profile.role.trim().lowercase()
        val isAdmin = role == "admin"
        val canControlRelay = role == "admin" || role == "staff"
        canControlRelayRole = canControlRelay
        val prefs = b.root.context.getSharedPreferences("thermal_guard_ui", 0)

        b.tvAccountName.text = profile.displayName.ifBlank { "Thermal Guard User" }
        b.tvAccountEmail.text = profile.email
        b.tvRoleBadge.text = if (isAdmin) "ADMIN" else "STAFF"
        b.tvAccessSummary.text = if (isAdmin) {
            "Full system access • Monitoring • Siren • Temperature thresholds • SMS backup • Staff management"
        } else {
            "Staff access • Live monitoring • Alerts • Health diagnostics • Siren ON / AUTO / OFF"
        }
        UiUtils.bindDetails(b.accountDetails, buildList {
            add(DetailRow("Role", if (isAdmin) "Administrator" else "Staff"))
            add(DetailRow("Account", if (profile.active) "Active" else "Disabled", UiUtils.statusColor(b.root.context, if (profile.active) "ONLINE" else "OFFLINE")))
            if (profile.createdAt > 0L) add(DetailRow("Member since", UiUtils.fullTime(profile.createdAt)))
            if (profile.approvedAt > 0L) add(DetailRow("Approved on", UiUtils.fullTime(profile.approvedAt)))
        })
        UiUtils.bindDetails(b.sessionDetails, listOf(
            DetailRow("Signed in as", profile.email.ifBlank { "--" }),
            DetailRow("App version", BuildConfig.VERSION_NAME),
            DetailRow("App build", BuildConfig.VERSION_CODE.toString())
        ))
        b.tvRelayAccess.text = if (isAdmin) {
            "Choose AUTO for normal operation. The Raspberry Pi will activate the siren when the configured critical threshold is reached."
        } else {
            "Siren control is available. AUTO lets the Raspberry Pi follow the configured critical-temperature logic."
        }

        b.thresholdCard.visibility = if (isAdmin) View.VISIBLE else View.GONE
        b.smsCard.visibility = if (isAdmin) View.VISIBLE else View.GONE
        b.switchNotifications.isChecked = prefs.getBoolean("notifications", true)
        val refreshValues = listOf("5", "10", "30", "60")
        b.autoRefresh.setAdapter(ArrayAdapter(b.root.context, android.R.layout.simple_dropdown_item_1line, refreshValues))
        b.autoRefresh.setText(prefs.getString("refresh", "5"), false)
        b.autoRefresh.setOnItemClickListener { _, _, position, _ ->
            if (position in refreshValues.indices) {
                prefs.edit().putString("refresh", refreshValues[position]).apply()
            }
        }

        b.switchNotifications.setOnCheckedChangeListener { _, checked ->
            if (_binding != null) host.updateNotificationPreference(checked)
        }

        listOf(b.btnAuto, b.btnOn, b.btnOff).forEach { it.isEnabled = false; it.alpha = 0.55f }
        listOf(b.etWarning, b.etCritical, b.btnSaveThresholds).forEach { it.isEnabled = isAdmin }
        listOf(
            b.switchSmsEnabled, b.etSmsPrimary, b.etSmsSecondary,
            b.etSmsTertiary, b.switchSmsWarning, b.switchSmsCritical,
            b.switchSmsRecovery, b.btnSaveSms, b.btnTestSms
        ).forEach { it.isEnabled = isAdmin }
        b.btnLogout.isEnabled = true
        b.autoRefresh.isEnabled = true
        b.switchNotifications.isEnabled = true

        liveListener = repo.observeLive({ system, zones ->
            val current = _binding ?: return@observeLive
            if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                return@observeLive
            }
            latestSystem = system
            latestZones = zones
            if (!current.etWarning.hasFocus()) current.etWarning.setText(system.warning_temp_c.toString())
            if (!current.etCritical.hasFocus()) current.etCritical.setText(system.critical_temp_c.toString())
            syncSirenSelection()
            renderControllerAvailability()
            renderNetworkAccess()

            if (isAdmin) {
                val gsm = system.gsm
                current.tvGsmStatus.text = when {
                    !gsm.enabled -> "SIM800L • Not reporting • GSM service is stopped or disabled on the Raspberry Pi"
                    gsm.ready && gsm.registered -> {
                        val signal = if (gsm.signal >= 0) " • CSQ ${gsm.signal}" else ""
                        val operator = gsm.operator.takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""
                        "SIM800L • ONLINE$operator$signal"
                    }
                    gsm.ready -> "SIM800L • Modem ready • Waiting for network"
                    else -> "SIM800L • OFFLINE${gsm.error.takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""}"
                }
                val context = current.root.context
                UiUtils.bindDetails(current.gsmDetails, listOf(
                    DetailRow("Modem", when {
                        gsm.ready -> "Responding"
                        gsm.enabled -> "Not responding"
                        else -> "Not reporting"
                    }, UiUtils.statusColor(context, if (gsm.ready) "ONLINE" else "OFFLINE")),
                    DetailRow("Network", if (gsm.registered) "Registered" else "Not registered"),
                    DetailRow("Operator", gsm.operator.ifBlank { "--" }),
                    DetailRow("Signal", UiUtils.signalText(gsm.signal)),
                    DetailRow("Last SMS", gsm.lastSmsStatus.ifBlank { "None" }),
                    DetailRow("Last SMS time", UiUtils.fullTime(gsm.lastSmsAt))
                ))
            }
        }, ::showToastSafely)

        handler.removeCallbacks(controllerWatchdog)
        handler.removeCallbacks(smsRealtimeSave)
        handler.post(controllerWatchdog)

        if (isAdmin) {
            smsListener = repo.observeSmsSettings({ settings ->
                val current = _binding ?: return@observeSmsSettings
                if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                    return@observeSmsSettings
                }
                suppressSmsRealtimeEvents = true
                if (!current.etSmsPrimary.hasFocus()) current.etSmsPrimary.setText(toPhilippineLocalDigits(settings.primary))
                if (!current.etSmsSecondary.hasFocus()) current.etSmsSecondary.setText(toPhilippineLocalDigits(settings.secondary))
                if (!current.etSmsTertiary.hasFocus()) current.etSmsTertiary.setText(toPhilippineLocalDigits(settings.tertiary))
                current.switchSmsEnabled.isChecked = settings.enabled
                current.switchSmsWarning.isChecked = settings.sendWarning
                current.switchSmsCritical.isChecked = settings.sendCritical
                current.switchSmsRecovery.isChecked = settings.sendRecovery
                current.tvSmsSyncStatus.text = "Realtime sync • Saved to Firebase • Philippines +63"
                smsRealtimeDirty = false
                suppressSmsRealtimeEvents = false
            }, ::showToastSafely)
        }

        b.relayGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || suppressSirenEvents || !canControlRelay || _binding == null) {
                return@addOnButtonCheckedListener
            }
            val controllerOnline = latestSystem?.let { UiUtils.isControllerOnline(it) } == true
            if (!controllerOnline) {
                showToastSafely("Raspberry Pi is offline. Siren command was not sent.")
                renderControllerAvailability()
                return@addOnButtonCheckedListener
            }
            val mode = when (id) {
                R.id.btnOn -> "on"
                R.id.btnOff -> "off"
                else -> "auto"
            }
            SirenConfirm.ask(requireContext(), mode, latestSystem,
                onConfirm = {
                    // The Pi may have dropped out while the confirmation was open.
                    if (latestSystem?.let { UiUtils.isControllerOnline(it) } != true) {
                        showToastSafely("Raspberry Pi is offline. Siren command was not sent.")
                        syncSirenSelection()
                        renderControllerAvailability()
                    } else {
                        _binding?.tvSirenRuntime?.text = "Sending ${mode.uppercase()} to Raspberry Pi…"
                        repo.setSirenMode(mode) { ok, err, _ ->
                            showToastSafely(if (ok) "Siren ${mode.uppercase()} command sent" else err ?: "Siren command failed")
                        }
                    }
                },
                // Cancelled: put the buttons back to the Pi's actual mode.
                onCancel = { syncSirenSelection() })
        }

        b.btnSaveThresholds.setOnClickListener {
            val current = _binding ?: return@setOnClickListener
            if (!isAdmin) return@setOnClickListener
            current.warningLayout.error = null
            current.criticalLayout.error = null
            val warning = current.etWarning.text?.toString()?.toDoubleOrNull()
            val critical = current.etCritical.text?.toString()?.toDoubleOrNull()
            when {
                warning == null -> current.warningLayout.error = "Enter a valid warning temperature"
                critical == null -> current.criticalLayout.error = "Enter a valid critical temperature"
                warning < 0 || critical > 80 || warning >= critical -> {
                    current.warningLayout.error = "Warning must be lower than critical"
                    current.criticalLayout.error = "Valid range is 0–80 °C"
                }
                else -> repo.setThresholds(warning, critical) { ok, err ->
                    showToastSafely(if (ok) "Thresholds saved" else err ?: "Save failed")
                }
            }
        }

        if (isAdmin) {
            val realtimeWatcher: () -> Unit = {
                if (!suppressSmsRealtimeEvents && _binding != null) {
                    smsRealtimeDirty = true
                    _binding?.tvSmsSyncStatus?.text = "Realtime sync • Waiting for a valid Philippine number…"
                    handler.removeCallbacks(smsRealtimeSave)
                    handler.postDelayed(smsRealtimeSave, SMS_REALTIME_DEBOUNCE_MS)
                }
            }
            b.etSmsPrimary.doAfterTextChanged { realtimeWatcher() }
            b.etSmsSecondary.doAfterTextChanged { realtimeWatcher() }
            b.etSmsTertiary.doAfterTextChanged { realtimeWatcher() }
            b.switchSmsEnabled.setOnCheckedChangeListener { _, _ -> realtimeWatcher() }
            b.switchSmsWarning.setOnCheckedChangeListener { _, _ -> realtimeWatcher() }
            b.switchSmsCritical.setOnCheckedChangeListener { _, _ -> realtimeWatcher() }
            b.switchSmsRecovery.setOnCheckedChangeListener { _, _ -> realtimeWatcher() }
        }

        b.btnSaveSms.setOnClickListener {
            val current = _binding ?: return@setOnClickListener
            if (!isAdmin) return@setOnClickListener
            current.smsPrimaryLayout.error = null
            current.smsSecondaryLayout.error = null
            current.smsTertiaryLayout.error = null

            val primary = normalizePhone(current.etSmsPrimary.text?.toString().orEmpty())
            val secondary = normalizeOptionalPhone(current.etSmsSecondary.text?.toString().orEmpty())
            val tertiary = normalizeOptionalPhone(current.etSmsTertiary.text?.toString().orEmpty())

            when {
                (current.switchSmsEnabled.isChecked || current.etSmsPrimary.text?.isNotBlank() == true) && primary == null ->
                    current.smsPrimaryLayout.error = "Enter a valid Philippine mobile number"
                current.etSmsSecondary.text?.isNotBlank() == true && secondary == null ->
                    current.smsSecondaryLayout.error = "Invalid mobile number"
                current.etSmsTertiary.text?.isNotBlank() == true && tertiary == null ->
                    current.smsTertiaryLayout.error = "Invalid mobile number"
                else -> {
                    val settings = SmsSettings(
                        enabled = current.switchSmsEnabled.isChecked,
                        primary = primary.orEmpty(),
                        secondary = secondary.orEmpty(),
                        tertiary = tertiary.orEmpty(),
                        sendWarning = current.switchSmsWarning.isChecked,
                        sendCritical = current.switchSmsCritical.isChecked,
                        sendRecovery = current.switchSmsRecovery.isChecked
                    )
                    repo.saveSmsSettings(settings) { ok, err ->
                        showToastSafely(if (ok) "SIM800L SMS settings saved" else err ?: "SMS settings save failed")
                    }
                }
            }
        }

        b.btnTestSms.setOnClickListener {
            val current = _binding ?: return@setOnClickListener
            if (!isAdmin) return@setOnClickListener
            current.smsPrimaryLayout.error = null
            val number = normalizePhone(current.etSmsPrimary.text?.toString().orEmpty())
            if (number == null) {
                current.smsPrimaryLayout.error = "Enter a valid primary number first"
                return@setOnClickListener
            }
            repo.requestTestSms(number) { ok, err ->
                showToastSafely(if (ok) "Test SMS request sent to Raspberry Pi" else err ?: "Test SMS request failed")
            }
        }

        renderNetworkAccess()

        b.btnCopyLocalUrl.setOnClickListener {
            localAccessUrl()?.let { copyAccessUrl("Local Thermal Guard URL", it) }
        }
        b.btnOpenLocalUrl.setOnClickListener {
            localAccessUrl()?.let(::openAccessUrl)
        }

        b.btnLogout.setOnClickListener {
            if (_binding != null) host.signOut()
        }
    }

    private fun localAccessUrl(): String? {
        val system = latestSystem ?: return null
        if (!UiUtils.isControllerOnline(system)) return null
        val port = system.controller_web_port.takeIf { it in 1..65535 } ?: 8080
        return system.controller_local_url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: system.controller_local_ip.takeIf { it.isNotBlank() }?.let { "http://$it:$port" }
    }


    private fun renderNetworkAccess() {
        val b = _binding ?: return
        val system = latestSystem
        val online = system?.let { UiUtils.isControllerOnline(it) } == true
        val localUrl = localAccessUrl()

        b.tvNetworkStatus.text = when {
            system == null -> "Waiting for Raspberry Pi heartbeat and network information…"
            !online -> "Raspberry Pi OFFLINE • saved IP addresses are disabled until a fresh heartbeat arrives."
            else -> {
                val host = system.controller_hostname.ifBlank { "thermalguard" }
                val iface = system.controller_local_interface.takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""
                "$host ONLINE$iface • web port ${system.controller_web_port.takeIf { it > 0 } ?: 8080}"
            }
        }

        b.tvLocalIp.text = when {
            !online -> "Controller offline"
            system?.controller_local_ip.isNullOrBlank() -> "Local IP not detected"
            else -> system?.controller_local_ip.orEmpty()
        }
        b.tvLocalUrl.text = localUrl ?: if (online) "Local URL unavailable" else "Waiting for controller…"

        listOf(b.btnCopyLocalUrl, b.btnOpenLocalUrl).forEach {
            it.isEnabled = localUrl != null
            it.alpha = if (localUrl != null) 1f else 0.5f
        }
    }

    private fun copyAccessUrl(label: String, url: String) {
        val context = context ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, url))
        showToastSafely("Address copied: $url")
    }

    private fun openAccessUrl(url: String) {
        val context = context ?: return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { showToastSafely("No browser is available to open this address") }
    }

    /** Show the Raspberry Pi's actual siren mode on the AUTO/ON/OFF buttons. */
    private fun syncSirenSelection() {
        val current = _binding ?: return
        val system = latestSystem ?: return
        val target = when (system.relay_mode) {
            "on" -> R.id.btnOn
            "off" -> R.id.btnOff
            else -> R.id.btnAuto
        }
        if (current.relayGroup.checkedButtonId != target) {
            suppressSirenEvents = true
            current.relayGroup.check(target)
            suppressSirenEvents = false
        }
    }

    private fun renderControllerAvailability() {
        val b = _binding ?: return
        val system = latestSystem
        val online = system?.let { UiUtils.isControllerOnline(it) } == true
        val relayEnabled = canControlRelayRole && online
        listOf(b.btnAuto, b.btnOn, b.btnOff).forEach {
            it.isEnabled = relayEnabled
            it.alpha = if (relayEnabled) 1f else 0.55f
        }

        b.tvSirenRuntime.text = when {
            system == null -> "Controller status • Waiting for Raspberry Pi heartbeat…"
            !online -> "Controller OFFLINE • Siren controls disabled • ${UiUtils.controllerAgeText(system)}"
            !system.relay_available -> "Controller ONLINE • Siren unavailable${system.relay_error.takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""}"
            system.relay_on -> "Controller ONLINE • Siren ON • Mode ${SirenConfirm.modeText(system)}"
            else -> "Controller ONLINE • Siren OFF • Mode ${SirenConfirm.modeText(system)}"
        }
        if (system != null) renderDetailCards(system, online)
    }

    private fun renderDetailCards(system: LiveSystem, online: Boolean) {
        val b = _binding ?: return
        val now = ServerClock.now()
        val warning = system.warning_temp_c
        val critical = system.critical_temp_c
        val hottest = latestZones
            .filter { !UiUtils.effectiveZoneStatus(it, online, now).equals("OFFLINE", true) && it.temperature != null }
            .maxByOrNull { it.temperature ?: Double.MIN_VALUE }
        UiUtils.bindDetails(b.thresholdDetails, listOf(
            DetailRow("Warning limit", String.format(java.util.Locale.US, "%.1f °C", warning), UiUtils.statusColor(b.root.context, "WARNING")),
            DetailRow("Critical limit", String.format(java.util.Locale.US, "%.1f °C", critical), UiUtils.statusColor(b.root.context, "CRITICAL")),
            DetailRow("Hottest zone", hottest?.name?.ifBlank { hottest.id } ?: "--"),
            DetailRow("Hottest reading", hottest?.temperature?.let { String.format(java.util.Locale.US, "%.2f °C", it) } ?: "--"),
            DetailRow("Margin", UiUtils.marginText(hottest?.temperature, warning, critical))
        ))
        UiUtils.bindDetails(b.settingsSirenDetails, UiUtils.sirenRows(system, online))
        UiUtils.bindDetails(b.networkDetails, listOf(
            DetailRow("Hostname", system.controller_hostname.ifBlank { "--" }),
            DetailRow("Network", system.controller_local_interface.ifBlank { "--" }),
            DetailRow("Web port", system.controller_web_port.toString()),
            DetailRow("Tailscale IP", system.controller_tailscale_ip.ifBlank { "Not installed" }),
            DetailRow("Last heartbeat", UiUtils.fullTime(UiUtils.controllerHeartbeatMillis(system)))
        ))
    }

    private fun setControlsEnabled(enabled: Boolean) {
        val b = _binding ?: return
        listOf(
            b.btnAuto, b.btnOn, b.btnOff, b.etWarning, b.etCritical, b.btnSaveThresholds,
            b.switchSmsEnabled, b.etSmsPrimary, b.etSmsSecondary, b.etSmsTertiary,
            b.switchSmsWarning, b.switchSmsCritical, b.switchSmsRecovery,
            b.btnSaveSms, b.btnTestSms, b.autoRefresh, b.switchNotifications, b.btnLogout
        ).forEach { it.isEnabled = enabled }
    }

    private fun showToastSafely(message: String) {
        if (!isAdded || _binding == null) return
        (activity as? MainActivity)?.toast(message)
    }

    private fun normalizeOptionalPhone(raw: String): String? = if (raw.isBlank()) "" else normalizePhone(raw)

    /**
     * Thermal Guard SMS recipients are intentionally Philippine mobile numbers only.
     * Accepted input: 09XXXXXXXXX, 9XXXXXXXXX, 639XXXXXXXXX, or +639XXXXXXXXX.
     * Firebase and SIM800L always receive the canonical E.164 value: +639XXXXXXXXX.
     */
    private fun normalizePhone(raw: String): String? {
        var compact = raw.trim().replace(Regex("[\\s()-]"), "")
        if (compact.isBlank()) return null
        if (compact.startsWith("+")) compact = compact.drop(1)
        val local = when {
            compact.matches(Regex("^09\\d{9}$")) -> compact.drop(1)
            compact.matches(Regex("^9\\d{9}$")) -> compact
            compact.matches(Regex("^639\\d{9}$")) -> compact.drop(2)
            else -> return null
        }
        return "+63$local"
    }

    private fun toPhilippineLocalDigits(raw: String): String {
        val normalized = normalizePhone(raw) ?: return raw.trim()
        return normalized.removePrefix("+63")
    }

    private fun saveSmsSettingsRealtime() {
        val current = _binding ?: return
        val primary = normalizePhone(current.etSmsPrimary.text?.toString().orEmpty())
        val secondary = normalizeOptionalPhone(current.etSmsSecondary.text?.toString().orEmpty())
        val tertiary = normalizeOptionalPhone(current.etSmsTertiary.text?.toString().orEmpty())

        current.smsPrimaryLayout.error = null
        current.smsSecondaryLayout.error = null
        current.smsTertiaryLayout.error = null

        val primaryRaw = current.etSmsPrimary.text?.toString().orEmpty()
        if ((current.switchSmsEnabled.isChecked || primaryRaw.isNotBlank()) && primary == null) {
            current.smsPrimaryLayout.error = "Use a Philippine mobile number, e.g. 9171234567"
            current.tvSmsSyncStatus.text = "Realtime sync • Primary number is incomplete"
            return
        }
        if (current.etSmsSecondary.text?.isNotBlank() == true && secondary == null) {
            current.smsSecondaryLayout.error = "Use a Philippine mobile number"
            current.tvSmsSyncStatus.text = "Realtime sync • Secondary number is invalid"
            return
        }
        if (current.etSmsTertiary.text?.isNotBlank() == true && tertiary == null) {
            current.smsTertiaryLayout.error = "Use a Philippine mobile number"
            current.tvSmsSyncStatus.text = "Realtime sync • Third number is invalid"
            return
        }

        val settings = SmsSettings(
            enabled = current.switchSmsEnabled.isChecked,
            primary = primary.orEmpty(),
            secondary = secondary.orEmpty(),
            tertiary = tertiary.orEmpty(),
            sendWarning = current.switchSmsWarning.isChecked,
            sendCritical = current.switchSmsCritical.isChecked,
            sendRecovery = current.switchSmsRecovery.isChecked
        )
        current.tvSmsSyncStatus.text = "Realtime sync • Saving…"
        repo.saveSmsSettings(settings) { ok, err ->
            val latest = _binding ?: return@saveSmsSettings
            if (ok) {
                smsRealtimeDirty = false
                latest.tvSmsSyncStatus.text = "Realtime sync • Saved to Firebase • Philippines +63"
            } else {
                latest.tvSmsSyncStatus.text = "Realtime sync • ${err ?: "Save failed"}"
            }
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacks(profileRetry)
        handler.removeCallbacks(controllerWatchdog)
        handler.removeCallbacks(smsRealtimeSave)
        liveListener?.let(repo::removeLiveObserver)
        smsListener?.let(repo::removeSmsSettingsObserver)
        liveListener = null
        smsListener = null
        initialized = false
        profileRetryCount = 0
        latestSystem = null
        canControlRelayRole = false
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val PROFILE_RETRY_MS = 250L
        private const val PROFILE_RETRY_LIMIT = 12
        private const val CONTROLLER_WATCHDOG_MS = 1_000L
        private const val SMS_REALTIME_DEBOUNCE_MS = 650L
    }
}
