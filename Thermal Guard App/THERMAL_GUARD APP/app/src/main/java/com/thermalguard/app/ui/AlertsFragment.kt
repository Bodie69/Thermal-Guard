package com.thermalguard.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.thermalguard.app.MainActivity
import com.thermalguard.app.data.ThermalRepository
import com.thermalguard.app.databinding.FragmentAlertsBinding
import com.thermalguard.app.model.AlertItem

/**
 * Alert history (newest 50 temperature alerts + newest 50 system alerts).
 *
 * Alerts are kept. This screen used to hide every alert after 60 seconds and,
 * on an admin's phone, delete it from Firebase - wiping the alert history,
 * including CRITICAL events and the alerts the Raspberry Pi replays after an
 * internet outage (which are always older than a minute when they arrive).
 * Admins can still delete a single alert with a long press.
 */
class AlertsFragment : Fragment() {
    private var _binding: FragmentAlertsBinding? = null
    private val repo = ThermalRepository()

    /** Only admins may delete alerts (enforced by database.rules.json too). */
    private val isAdmin: Boolean
        get() = (activity as? MainActivity)?.profile?.role?.trim()?.equals("admin", ignoreCase = true) == true

    private val adapter by lazy { AlertAdapter(if (isAdmin) ::confirmDelete else null) }
    private var alertObserver: ThermalRepository.AlertObserverHandle? = null
    private var all = emptyList<AlertItem>()
    private var filter = "ALL"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        state: Bundle?
    ): View = FragmentAlertsBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, state: Bundle?) {
        val b = _binding ?: return
        b.alertList.layoutManager = LinearLayoutManager(view.context)
        b.alertList.adapter = adapter
        b.tvAlertsHint.text = if (isAdmin) {
            "Newest 100 alerts are kept • Hold an alert to delete it"
        } else {
            "Newest 100 alerts are kept"
        }
        listOf("ALL", "CRITICAL", "WARNING", "SYSTEM").forEach {
            b.tabs.addTab(b.tabs.newTab().setText(it))
        }
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                filter = tab.text?.toString().orEmpty()
                render()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        alertObserver = repo.observeAlerts({ rows ->
            if (_binding == null) return@observeAlerts
            all = rows
            render()
        }, { error ->
            if (_binding != null && isAdded) (activity as? MainActivity)?.toast(error)
        })
    }

    private fun confirmDelete(alert: AlertItem) {
        val context = context ?: return
        if (_binding == null || !isAdmin) return
        val title = alert.type.ifBlank { "Alert" }.replace("_", " ")
        MaterialAlertDialogBuilder(context)
            .setTitle("Delete alert?")
            .setMessage("$title will be permanently removed from the alert history for everyone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                if (_binding == null) return@setPositiveButton
                all = all.filterNot { it.key == alert.key && it.sourcePath == alert.sourcePath }
                render()
                repo.deleteAlert(alert) { ok, error ->
                    if (!ok && _binding != null && isAdded) {
                        (activity as? MainActivity)?.toast(error ?: "Unable to delete alert")
                    }
                }
            }
            .show()
    }

    private fun render() {
        val b = _binding ?: return
        val fresh = all
        val rows = when (filter) {
            "CRITICAL" -> fresh.filter {
                it.severity.equals("CRITICAL", true) ||
                    it.type.contains("CRITICAL", true) ||
                    it.type.contains("OFFLINE", true)
            }
            "WARNING" -> fresh.filter {
                it.severity.equals("WARNING", true) || it.type.contains("WARNING", true)
            }
            "SYSTEM" -> fresh.filter {
                !it.type.contains("CRITICAL", true) &&
                    !it.type.contains("WARNING", true) &&
                    !it.type.contains("OFFLINE", true)
            }
            else -> fresh
        }
        adapter.submitList(rows)
        b.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        b.alertList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        alertObserver?.let(repo::removeAlertsObserver)
        alertObserver = null
        _binding?.alertList?.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
