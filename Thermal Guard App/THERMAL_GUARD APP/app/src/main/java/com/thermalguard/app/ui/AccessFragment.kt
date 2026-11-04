package com.thermalguard.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.thermalguard.app.MainActivity
import com.thermalguard.app.databinding.FragmentAccessBinding

/** Safe fourth navigation destination for approved staff accounts. */
class AccessFragment : Fragment() {
    private var _binding: FragmentAccessBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        state: Bundle?
    ): View = FragmentAccessBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, state: Bundle?) {
        val b = _binding ?: return
        val host = activity as? MainActivity ?: return
        val profile = host.profile
        val role = profile?.role?.trim()?.lowercase().orEmpty()

        b.tvAccountName.text = profile?.displayName?.ifBlank { "Thermal Guard User" } ?: "Thermal Guard User"
        b.tvAccountEmail.text = profile?.email.orEmpty()
        b.tvRoleBadge.text = if (role == "admin") "ADMIN" else "STAFF"
        b.tvAccessDescription.text = if (role == "admin") {
            "Admin access is active. Use the Staff tab to approve registrations and manage staff accounts."
        } else {
            "Your staff access is active. You can monitor live temperatures, view alerts and diagnostics, control the relay, and manage your own app preferences."
        }
        val context = b.root.context
        val isAdmin = role == "admin"
        val active = profile?.active == true
        UiUtils.bindDetails(b.accountRows, buildList {
            add(DetailRow("Role", if (isAdmin) "Administrator" else "Staff"))
            add(DetailRow("Account", if (active) "Active" else "Disabled", UiUtils.statusColor(context, if (active) "ONLINE" else "OFFLINE")))
            profile?.createdAt?.takeIf { it > 0L }?.let { add(DetailRow("Member since", UiUtils.fullTime(it))) }
            profile?.approvedAt?.takeIf { it > 0L }?.let { add(DetailRow("Approved on", UiUtils.fullTime(it))) }
        })
        val allowed = UiUtils.statusColor(context, "ONLINE")
        val restricted = UiUtils.statusColor(context, "")
        fun permission(label: String, adminOnly: Boolean) =
            if (!adminOnly || isAdmin) DetailRow(label, "Allowed", allowed) else DetailRow(label, "Admin only", restricted)
        UiUtils.bindDetails(b.permissionRows, listOf(
            permission("Live monitoring", false),
            permission("Alerts and health", false),
            permission("Siren ON / AUTO / OFF", false),
            permission("Notifications and preferences", false),
            permission("Temperature thresholds", true),
            permission("SMS backup settings", true),
            permission("Staff management", true)
        ))
        b.btnOpenSettings.setOnClickListener {
            if (_binding != null) (activity as? MainActivity)?.openSettings()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
