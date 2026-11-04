package com.thermalguard.app.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.database.ValueEventListener
import com.thermalguard.app.MainActivity
import com.thermalguard.app.data.ThermalRepository
import com.thermalguard.app.databinding.FragmentStaffBinding
import com.thermalguard.app.model.AppUser
import com.thermalguard.app.model.StaffRequest
import com.thermalguard.app.model.StaffRow

class StaffFragment : Fragment() {
    private var _binding: FragmentStaffBinding? = null
    private val repo = ThermalRepository()
    private var requests = emptyMap<String, StaffRequest>()
    private var users = emptyMap<String, AppUser>()
    private var requestsListener: ValueEventListener? = null
    private var usersListener: ValueEventListener? = null
    private var mode = "PENDING"
    private var search = ""

    private val adapter by lazy {
        StaffAdapter(
            mode = { mode },
            onApprove = { row -> confirmApprove(row) },
            onReject = { row -> confirmReject(row) },
            onEdit = { row -> showEditDialog(row) },
            onRemove = { row -> confirmRemove(row) },
            onToggleActive = { row -> if (row.active) confirmDisable(row) else toggleAccess(row) }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        state: Bundle?
    ): View = FragmentStaffBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, state: Bundle?) {
        val host = activity as? MainActivity ?: return
        val isAdmin = host.profile?.role?.trim()?.equals("admin", ignoreCase = true) == true
        if (!isAdmin) {
            host.toast("Admin only")
            // Do not pop the FragmentManager during cold-start restoration. MainActivity owns routing.
            return
        }

        val b = _binding ?: return
        b.staffList.layoutManager = LinearLayoutManager(view.context)
        b.staffList.adapter = adapter
        listOf("PENDING", "ACTIVE", "DISABLED").forEach {
            b.tabs.addTab(b.tabs.newTab().setText(it))
        }

        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                mode = tab.text?.toString().orEmpty()
                render()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        b.searchStaff.doOnTextChanged { text, _, _, _ ->
            search = text?.toString().orEmpty().trim().lowercase()
            render()
        }

        requestsListener = repo.observeStaffRequests({ data ->
            if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED) || _binding == null) {
                return@observeStaffRequests
            }
            requests = data
            render()
        }, ::showErrorSafely)

        usersListener = repo.observeUsers({ data ->
            if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED) || _binding == null) {
                return@observeUsers
            }
            users = data
            render()
        }, ::showErrorSafely)
    }

    private fun confirmApprove(row: StaffRow) {
        val context = context ?: return
        val name = row.displayName.ifBlank { "Unnamed user" }
        val email = row.email.ifBlank { "(no email on request)" }
        MaterialAlertDialogBuilder(context)
            .setTitle("Approve staff access?")
            .setMessage(
                "Name: $name\n" +
                "Google email: $email\n" +
                "Role: Staff\n\n" +
                "Check that the email belongs to the person you expect. Once approved they can view live " +
                "temperatures, alerts and diagnostics, and will receive alarm notifications on their phone."
            )
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("APPROVE") { _, _ -> approve(row) }
            .show()
    }

    private fun confirmDisable(row: StaffRow) {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle("Disable staff account?")
            .setMessage(
                "${row.displayName.ifBlank { row.email }} will immediately lose access to live monitoring and alerts. " +
                "You can enable the account again at any time."
            )
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("DISABLE") { _, _ -> toggleAccess(row) }
            .show()
    }

    private fun approve(row: StaffRow) {
        val req = requests[row.uid] ?: return
        repo.approveStaff(row.uid, req) { ok, err ->
            showToastSafely(if (ok) "${row.displayName.ifBlank { "Staff" }} approved" else err ?: "Approval failed")
        }
    }

    private fun confirmReject(row: StaffRow) {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle("Reject staff request?")
            .setMessage("${row.displayName.ifBlank { row.email }} will not receive Thermal Guard access. They can request access again later.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("REJECT") { _, _ ->
                repo.rejectStaff(row.uid) { ok, err ->
                    showToastSafely(if (ok) "Request rejected" else err ?: "Reject failed")
                }
            }
            .show()
    }

    private fun showEditDialog(row: StaffRow) {
        val context = context ?: return
        val inputLayout = TextInputLayout(context).apply {
            hint = "Staff display name"
            setPadding(24, 8, 24, 0)
        }
        val input = TextInputEditText(inputLayout.context).apply {
            setText(row.displayName)
            setSelection(text?.length ?: 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        inputLayout.addView(input)

        MaterialAlertDialogBuilder(context)
            .setTitle("Edit staff")
            .setMessage(row.email)
            .setView(inputLayout)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SAVE") { _, _ ->
                repo.updateStaffProfile(row.uid, input.text?.toString().orEmpty()) { ok, err ->
                    showToastSafely(if (ok) "Staff profile updated" else err ?: "Update failed")
                }
            }
            .show()
    }

    private fun confirmRemove(row: StaffRow) {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle("Remove staff access?")
            .setMessage("This removes Thermal Guard app access for ${row.displayName.ifBlank { row.email }}. It does not delete their Google account. They can request access again later.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("REMOVE") { _, _ ->
                repo.removeStaffAccess(row.uid) { ok, err ->
                    showToastSafely(if (ok) "Staff access removed" else err ?: "Remove failed")
                }
            }
            .show()
    }

    private fun toggleAccess(row: StaffRow) {
        repo.setUserActive(row.uid, !row.active) { ok, err ->
            showToastSafely(
                if (ok) {
                    if (row.active) "Staff account disabled" else "Staff account enabled"
                } else {
                    err ?: "Update failed"
                }
            )
        }
    }

    private fun showErrorSafely(message: String) = showToastSafely(message)

    private fun showToastSafely(message: String) {
        if (!isAdded || _binding == null) return
        (activity as? MainActivity)?.toast(message)
    }

    private fun render() {
        val b = _binding ?: return
        val staffUsers = users.filterValues { it.role.trim().equals("staff", ignoreCase = true) }
        val pendingCount = requests.values.count { it.status == "pending" }
        val activeCount = staffUsers.values.count { it.active }
        val disabledCount = staffUsers.values.count { !it.active }

        b.tvPendingCount.text = pendingCount.toString()
        b.tvActiveCount.text = activeCount.toString()
        b.tvDisabledCount.text = disabledCount.toString()

        var rows = when (mode) {
            "PENDING" -> requests.filterValues { it.status == "pending" }.map { (uid, r) ->
                StaffRow(uid, r.displayName, r.email, "staff", true, r.requestedAt, r.status)
            }
            "DISABLED" -> staffUsers.filterValues { !it.active }.map { (uid, u) ->
                StaffRow(uid, u.displayName, u.email, u.role, u.active)
            }
            else -> staffUsers.filterValues { it.active }.map { (uid, u) ->
                StaffRow(uid, u.displayName, u.email, u.role, u.active)
            }
        }

        if (search.isNotBlank()) {
            rows = rows.filter {
                it.displayName.lowercase().contains(search) || it.email.lowercase().contains(search)
            }
        }
        rows = rows.sortedBy { it.displayName.lowercase() }

        adapter.submitList(rows)
        b.emptyText.text = if (search.isBlank()) "No accounts in this category." else "No staff match your search."
        b.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        b.staffList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        requestsListener?.let(repo::removeStaffRequestsObserver)
        usersListener?.let(repo::removeUsersObserver)
        requestsListener = null
        usersListener = null
        _binding?.staffList?.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
