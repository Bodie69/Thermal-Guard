package com.thermalguard.app.ui

import com.thermalguard.app.data.ServerClock
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.database.ValueEventListener
import com.thermalguard.app.MainActivity
import com.thermalguard.app.R
import com.thermalguard.app.data.ThermalRepository
import com.thermalguard.app.databinding.FragmentDashboardBinding
import com.thermalguard.app.model.LiveSystem
import com.thermalguard.app.model.ZoneReading

class DashboardFragment : Fragment() {
    private var _binding: FragmentDashboardBinding? = null
    private val repo = ThermalRepository()
    private val adapter = ZoneAdapter()
    private var liveListener: ValueEventListener? = null
    private var suppressSirenEvents = false
    private var pendingSirenRequestId: String? = null
    private var pendingSirenMode: String? = null
    private var latestSystem: LiveSystem? = null
    private var latestZones: List<ZoneReading> = emptyList()
    private var isAdminUser = false
    private var lastControllerOnline: Boolean? = null
    private var recoveryNoticeUntilMs: Long = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val controllerWatchdog = object : Runnable {
        override fun run() {
            if (_binding == null) return
            renderLatestState()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return requireNotNull(_binding).root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val b = _binding ?: return
        b.zoneList.layoutManager = LinearLayoutManager(requireContext())
        b.zoneList.adapter = adapter

        val host = activity as? MainActivity ?: return
        isAdminUser = host.profile?.role?.trim()?.equals("admin", ignoreCase = true) == true
        b.sirenCard.visibility = if (isAdminUser) View.VISIBLE else View.GONE
        b.btnAlerts.setOnClickListener { host.openAlerts() }

        b.relayGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked || suppressSirenEvents || !isAdminUser || _binding == null) {
                return@addOnButtonCheckedListener
            }

            val controllerOnline = latestSystem?.let { UiUtils.isControllerOnline(it) } == true
            if (!controllerOnline) {
                host.toast("Raspberry Pi is offline. Siren command was not sent.")
                renderLatestState()
                return@addOnButtonCheckedListener
            }

            val mode = when (checkedId) {
                R.id.btnOn -> "on"
                R.id.btnOff -> "off"
                else -> "auto"
            }

            SirenConfirm.ask(requireContext(), mode, latestSystem,
                onConfirm = { sendSirenMode(mode) },
                // Cancelled: put the buttons back to the Pi's actual mode.
                onCancel = { renderLatestState() })
        }

        liveListener = repo.observeLive({ system, zones ->
            if (_binding == null || !isAdded ||
                !viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)
            ) return@observeLive

            val now = ServerClock.now()
            val controllerOnline = UiUtils.isControllerOnline(system, now)
            if (lastControllerOnline == false && controllerOnline) {
                recoveryNoticeUntilMs = now + RECOVERY_NOTICE_MS
                host.toast("Raspberry Pi reconnected • refreshing sensors automatically")
            }
            lastControllerOnline = controllerOnline

            latestSystem = system
            latestZones = zones
            adapter.submitList(zones)
            renderLatestState()
        }, { error ->
            if (isAdded && _binding != null &&
                viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)
            ) host.toast(error)
        })

        handler.removeCallbacks(controllerWatchdog)
        handler.post(controllerWatchdog)
    }

    private fun sendSirenMode(mode: String) {
        val current0 = _binding ?: return
        val host = activity as? MainActivity ?: return
        // The Pi may have dropped out while the confirmation was open.
        if (latestSystem?.let { UiUtils.isControllerOnline(it) } != true) {
            host.toast("Raspberry Pi is offline. Siren command was not sent.")
            renderLatestState()
            return
        }
        current0.tvSirenCommand.text = "Sending ${mode.uppercase()} to Raspberry Pi…"
        current0.tvSirenCommand.setTextColor(ContextCompat.getColor(requireContext(), R.color.tg_amber))

        repo.setSirenMode(mode) { ok, err, requestId ->
            if (!isAdded || _binding == null ||
                !viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)
            ) return@setSirenMode

            val current = _binding ?: return@setSirenMode
            if (ok && !requestId.isNullOrBlank()) {
                pendingSirenRequestId = requestId
                pendingSirenMode = mode
                current.tvSirenCommand.text = "Command sent • waiting for Raspberry Pi confirmation…"
                current.tvSirenCommand.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.tg_amber)
                )
            } else {
                pendingSirenRequestId = null
                pendingSirenMode = null
                current.tvSirenCommand.text = err ?: "Siren command failed"
                current.tvSirenCommand.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.tg_red)
                )
            }
        }
    }

    private fun renderLatestState() {
        val current = _binding ?: return
        val system = latestSystem
        if (system == null) {
            current.tvSystem.text = "OFFLINE • no heartbeat received"
            UiUtils.styleChip(current.chipSystem, "OFFLINE")
            adapter.setControllerOnline(false)
            adapter.notifyDataSetChanged()
            setSirenButtonsEnabled(false)
            UiUtils.bindDetails(current.piDetails, listOf(DetailRow("Status", "Waiting for first heartbeat")))
            UiUtils.bindDetails(current.sirenDetails, emptyList())
            return
        }

        val now = ServerClock.now()
        val controllerOnline = UiUtils.isControllerOnline(system, now)
        adapter.setThresholds(system.warning_temp_c, system.critical_temp_c)
        adapter.setControllerOnline(controllerOnline)
        adapter.notifyDataSetChanged()
        renderDetails(system, controllerOnline, now)

        // Dashboard header is intentionally controller-only. Sensor health is
        // shown by each zone card and on the Health screen, so the Raspberry Pi
        // badge can never be confused with a sensor-level DEGRADED state.
        current.tvSystem.text = when {
            !controllerOnline -> "OFFLINE • heartbeat lost"
            now < recoveryNoticeUntilMs -> "ONLINE • reconnected • refreshing sensors…"
            else -> "ONLINE • ${UiUtils.controllerAgeText(system, now)}"
        }
        UiUtils.styleChip(current.chipSystem, if (controllerOnline) "ONLINE" else "OFFLINE")

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

        setSirenButtonsEnabled(isAdminUser && controllerOnline)

        if (!controllerOnline) {
            current.chipRelayMode.text = "OFFLINE"
            current.tvSirenState.text = "Actual siren: RASPBERRY PI OFFLINE"
            current.tvSirenDetail.text = "Heartbeat lost • GPIO state cannot be verified"
            current.sirenStateDot.setBackgroundResource(R.drawable.bg_status_dot_error)
            current.tvSirenCommand.text = "Control disabled until Raspberry Pi reconnects"
            current.tvSirenCommand.setTextColor(ContextCompat.getColor(requireContext(), R.color.tg_red))
            return
        }

        // Raspberry Pi is alive. Relay state can now be trusted.
        current.chipRelayMode.text = system.relay_mode.uppercase()
        when {
            !system.relay_available -> {
                current.tvSirenState.text = "Actual siren: CONTROLLER UNAVAILABLE"
                current.tvSirenDetail.text = system.relay_error.ifBlank { "Raspberry Pi GPIO relay is unavailable" }
                current.sirenStateDot.setBackgroundResource(R.drawable.bg_status_dot_error)
            }
            system.relay_on -> {
                current.tvSirenState.text = "Actual siren: ON"
                current.tvSirenDetail.text = when (system.relay_mode) {
                    "auto" -> "AUTO triggered by critical temperature"
                    "on" -> "Manual ON command active"
                    else -> "GPIO relay output is energized"
                }
                current.sirenStateDot.setBackgroundResource(R.drawable.bg_status_dot_on)
            }
            else -> {
                current.tvSirenState.text = "Actual siren: OFF"
                current.tvSirenDetail.text = when (system.relay_mode) {
                    "auto" -> if (system.relay_auto_demand) {
                        "AUTO requested ON but output is currently OFF"
                    } else {
                        "AUTO armed • no critical-temperature demand"
                    }
                    "off" -> system.relay_off_remaining_seconds?.let {
                        "Manual OFF • returns to AUTO in ${((it + 59) / 60).coerceAtLeast(1)} min"
                    } ?: "Forced OFF by control mode"
                    else -> "GPIO relay output is off"
                }
                current.sirenStateDot.setBackgroundResource(R.drawable.bg_status_dot_off)
            }
        }

        val pendingId = pendingSirenRequestId
        if (!pendingId.isNullOrBlank() && system.relay_last_command_id == pendingId) {
            val requestedMode = pendingSirenMode.orEmpty().uppercase()
            if (system.relay_last_command_ok && system.relay_last_command_mode == pendingSirenMode) {
                current.tvSirenCommand.text = "✓ $requestedMode confirmed by Raspberry Pi"
                current.tvSirenCommand.setTextColor(ContextCompat.getColor(requireContext(), R.color.tg_green))
            } else {
                current.tvSirenCommand.text = "Raspberry Pi could not apply $requestedMode"
                current.tvSirenCommand.setTextColor(ContextCompat.getColor(requireContext(), R.color.tg_red))
            }
            pendingSirenRequestId = null
            pendingSirenMode = null
        } else if (pendingId.isNullOrBlank() && system.relay_last_command_id.isNotBlank()) {
            current.tvSirenCommand.text = if (system.relay_last_command_ok) {
                "✓ Last ${system.relay_last_command_mode.uppercase()} command confirmed by Raspberry Pi"
            } else {
                "Last siren command was not applied"
            }
            current.tvSirenCommand.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (system.relay_last_command_ok) R.color.tg_green else R.color.tg_red
                )
            )
        }
    }

    private fun renderDetails(system: LiveSystem, online: Boolean, now: Long) {
        val b = _binding ?: return
        val total = if (latestZones.isNotEmpty()) latestZones.size else system.total_sensors
        UiUtils.bindDetails(b.piDetails, listOf(
            DetailRow("Hostname", system.controller_hostname.ifBlank { "--" }),
            DetailRow("Software", system.controller_version.ifBlank { "--" }),
            DetailRow("Uptime", if (online) UiUtils.uptimeText(system.controller_uptime_seconds) else "--"),
            DetailRow("Sensors online", "${UiUtils.effectiveOnlineCount(latestZones, online, now)} of $total"),
            DetailRow("System health", if (online) system.system_health.ifBlank { "--" } else "OFFLINE", UiUtils.statusColor(requireContext(), if (online) system.system_health else "OFFLINE")),
            DetailRow("Local IP", system.controller_local_ip.ifBlank { "--" })
        ))
        UiUtils.bindDetails(b.sirenDetails, UiUtils.sirenRows(system, online))
    }

    private fun setSirenButtonsEnabled(enabled: Boolean) {
        val b = _binding ?: return
        listOf(b.btnOn, b.btnAuto, b.btnOff).forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.55f
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacks(controllerWatchdog)
        liveListener?.let(repo::removeLiveObserver)
        liveListener = null
        latestSystem = null
        latestZones = emptyList()
        lastControllerOnline = null
        recoveryNoticeUntilMs = 0L
        _binding?.zoneList?.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val WATCHDOG_INTERVAL_MS = 1_000L
        private const val RECOVERY_NOTICE_MS = 5_000L
    }
}
