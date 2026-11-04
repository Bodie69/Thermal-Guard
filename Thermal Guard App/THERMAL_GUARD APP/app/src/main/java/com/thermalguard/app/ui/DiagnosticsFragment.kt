package com.thermalguard.app.ui

import com.thermalguard.app.data.ServerClock
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.database.ValueEventListener
import com.thermalguard.app.MainActivity
import com.thermalguard.app.data.ThermalRepository
import com.thermalguard.app.databinding.FragmentDiagnosticsBinding
import com.thermalguard.app.model.LiveSystem
import com.thermalguard.app.model.ZoneReading

class DiagnosticsFragment : Fragment() {
    private var _binding: FragmentDiagnosticsBinding? = null
    private val repo = ThermalRepository()
    private val adapter = DiagnosticAdapter()
    private var liveListener: ValueEventListener? = null
    private var latestSystem: LiveSystem? = null
    private var latestZones: List<ZoneReading> = emptyList()
    private var lastControllerOnline: Boolean? = null
    private var recoveryNoticeUntilMs: Long = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val controllerWatchdog = object : Runnable {
        override fun run() {
            if (_binding == null) return
            renderControllerState()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        state: Bundle?
    ): View = FragmentDiagnosticsBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, state: Bundle?) {
        val b = _binding ?: return
        b.diagnosticList.layoutManager = LinearLayoutManager(view.context)
        b.diagnosticList.adapter = adapter

        liveListener = repo.observeLive({ system, zones ->
            if (_binding == null ||
                !viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)
            ) return@observeLive

            val now = ServerClock.now()
            val controllerOnline = UiUtils.isControllerOnline(system, now)
            if (lastControllerOnline == false && controllerOnline) {
                recoveryNoticeUntilMs = now + RECOVERY_NOTICE_MS
            }
            lastControllerOnline = controllerOnline

            latestSystem = system
            latestZones = zones
            adapter.submitList(zones)
            renderControllerState()
            b.emptyText.visibility = if (zones.isEmpty()) View.VISIBLE else View.GONE
        }, { error ->
            if (_binding != null && isAdded) {
                (activity as? MainActivity)?.toast(error)
            }
        })

        b.btnRefresh.setOnClickListener {
            renderControllerState()
            (activity as? MainActivity)?.toast("Health status refreshed from live heartbeat")
        }

        handler.removeCallbacks(controllerWatchdog)
        handler.post(controllerWatchdog)
    }

    private fun renderControllerState() {
        val b = _binding ?: return
        val system = latestSystem
        if (system == null) {
            adapter.setControllerOnline(false)
            adapter.notifyDataSetChanged()
            UiUtils.styleChip(b.chipController, "OFFLINE")
            b.tvControllerStatus.text = "No Raspberry Pi heartbeat received yet"
            b.tvControllerDetail.text = "All sensors remain offline until a fresh controller heartbeat and sensor reading arrive."
            UiUtils.bindDetails(b.controllerDetails, listOf(DetailRow("Status", "Waiting for first heartbeat")))
            return
        }

        val now = ServerClock.now()
        val online = UiUtils.isControllerOnline(system, now)
        val onlineSensors = UiUtils.effectiveOnlineCount(latestZones, online, now)
        val total = if (latestZones.isNotEmpty()) latestZones.size else system.total_sensors.coerceAtLeast(1)

        adapter.setControllerOnline(online)
        adapter.notifyDataSetChanged()
        UiUtils.styleChip(b.chipController, if (online) "ONLINE" else "OFFLINE")
        val context = b.root.context
        UiUtils.bindDetails(b.controllerDetails, listOf(
            DetailRow("Hostname", system.controller_hostname.ifBlank { "--" }),
            DetailRow("Software", system.controller_version.ifBlank { "--" }),
            DetailRow("Uptime", if (online) UiUtils.uptimeText(system.controller_uptime_seconds) else "--"),
            DetailRow("Last heartbeat", UiUtils.fullTime(UiUtils.controllerHeartbeatMillis(system))),
            DetailRow("System health", if (online) system.system_health.ifBlank { "--" } else "OFFLINE", UiUtils.statusColor(context, if (online) system.system_health else "OFFLINE")),
            DetailRow("Sensors online", "$onlineSensors of $total"),
            DetailRow("Sensor mode", system.sensor_mode.ifBlank { "--" }),
            DetailRow("Siren relay", when {
                !online -> "Unknown"
                !system.relay_available -> "Unavailable"
                system.relay_on -> "ON (${system.relay_mode.uppercase()})"
                else -> "OFF (${system.relay_mode.uppercase()})"
            }),
            DetailRow("SMS modem", when {
                !online -> "Unknown"
                system.gsm.ready && system.gsm.registered -> "Online"
                system.gsm.ready -> "Waiting for network"
                system.gsm.enabled -> "Offline"
                else -> "Not reporting"
            }),
            DetailRow("Local IP", system.controller_local_ip.ifBlank { "--" }),
            DetailRow("Network", system.controller_local_interface.ifBlank { "--" }),
            DetailRow("Web port", system.controller_web_port.toString())
        ))

        if (online) {
            b.tvControllerStatus.text = "ONLINE • ${UiUtils.controllerAgeText(system, now)} • $onlineSensors/$total sensors online"
            val host = system.controller_hostname.ifBlank { "thermalguard" }
            val version = system.controller_version.ifBlank { "version unknown" }
            b.tvControllerDetail.text = when {
                now < recoveryNoticeUntilMs -> "$host • $version • connection restored; requesting fresh sensor readings now"
                onlineSensors == total -> "$host • $version • controller and all sensors are synchronized"
                else -> "$host • $version • Pi is online; each sensor stays offline until it produces a fresh valid reading"
            }
        } else {
            b.tvControllerStatus.text = "OFFLINE • ${UiUtils.controllerAgeText(system, now)} • 0/$total sensors available"
            b.tvControllerDetail.text = "Heartbeat timeout exceeded 15 seconds. Old Firebase temperatures are hidden and all sensors are forced OFFLINE."
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
        _binding?.diagnosticList?.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val WATCHDOG_INTERVAL_MS = 1_000L
        private const val RECOVERY_NOTICE_MS = 5_000L
    }
}
