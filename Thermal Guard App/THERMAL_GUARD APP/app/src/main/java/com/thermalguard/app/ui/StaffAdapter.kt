package com.thermalguard.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.thermalguard.app.databinding.ItemStaffBinding
import com.thermalguard.app.model.StaffRow

class StaffAdapter(
    private val mode: () -> String,
    private val onApprove: (StaffRow) -> Unit,
    private val onReject: (StaffRow) -> Unit,
    private val onEdit: (StaffRow) -> Unit,
    private val onRemove: (StaffRow) -> Unit,
    private val onToggleActive: (StaffRow) -> Unit
) : ListAdapter<StaffRow, StaffAdapter.Holder>(Diff) {

    object Diff : DiffUtil.ItemCallback<StaffRow>() {
        override fun areItemsTheSame(a: StaffRow, b: StaffRow) = a.uid == b.uid
        override fun areContentsTheSame(a: StaffRow, b: StaffRow) = a == b
    }

    class Holder(val b: ItemStaffBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemStaffBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        with(holder.b) {
            tvInitials.text = row.displayName.split(" ").filter { it.isNotBlank() }.take(2)
                .joinToString("") { it.take(1).uppercase() }.ifBlank { "TG" }
            tvName.text = row.displayName.ifBlank { "Unnamed staff" }
            tvEmail.text = row.email
            val role = row.role.ifBlank { "staff" }.replaceFirstChar { it.uppercase() }
            UiUtils.bindDetails(detailRows, if (mode() == "PENDING") {
                listOf(
                    DetailRow("Requested role", role),
                    DetailRow("Request status", row.requestStatus.ifBlank { "pending" }.replaceFirstChar { it.uppercase() }, UiUtils.statusColor(root.context, "WARNING")),
                    DetailRow("Requested on", UiUtils.fullTime(row.requestedAt))
                )
            } else {
                listOf(
                    DetailRow("Role", role),
                    DetailRow("Access", if (row.active) "Active" else "Disabled", UiUtils.statusColor(root.context, if (row.active) "ONLINE" else "OFFLINE"))
                )
            })

            btnEdit.visibility = View.GONE
            spacerEdit.visibility = View.GONE
            btnSecondary.visibility = View.VISIBLE
            btnPrimary.visibility = View.VISIBLE

            if (mode() == "PENDING") {
                btnSecondary.text = "REJECT"
                btnPrimary.text = "APPROVE"
                btnSecondary.setOnClickListener { onReject(row) }
                btnPrimary.setOnClickListener { onApprove(row) }
            } else {
                btnEdit.visibility = View.VISIBLE
                spacerEdit.visibility = View.VISIBLE
                btnEdit.text = "EDIT"
                btnSecondary.text = "REMOVE"
                btnPrimary.text = if (row.active) "DISABLE" else "ENABLE"
                btnEdit.setOnClickListener { onEdit(row) }
                btnSecondary.setOnClickListener { onRemove(row) }
                btnPrimary.setOnClickListener { onToggleActive(row) }
            }
        }
    }
}
