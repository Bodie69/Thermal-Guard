package com.thermalguard.app.ui

import com.thermalguard.app.data.ServerClock
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.thermalguard.app.R
import com.thermalguard.app.databinding.ItemDiagnosticBinding
import com.thermalguard.app.model.ZoneReading

class DiagnosticAdapter : ListAdapter<ZoneReading, DiagnosticAdapter.Holder>(Diff) {
    private var controllerOnline: Boolean = false

    fun setControllerOnline(online: Boolean) {
        if (controllerOnline != online) {
            controllerOnline = online
            notifyDataSetChanged()
        }
    }
    object Diff : DiffUtil.ItemCallback<ZoneReading>() {
        override fun areItemsTheSame(a: ZoneReading, b: ZoneReading) = a.id == b.id
        override fun areContentsTheSame(a: ZoneReading, b: ZoneReading) = a == b
    }
    class Holder(val b: ItemDiagnosticBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemDiagnosticBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val z = getItem(position)
        with(holder.b) {
            tvName.text = z.name.ifBlank { z.id.replaceFirstChar { it.uppercase() } }
            val now = ServerClock.now()
            val safeStatus = UiUtils.effectiveZoneStatus(z, controllerOnline, now)
            UiUtils.styleChip(chipStatus, safeStatus)
            tvTemperature.setTextColor(UiUtils.statusColor(root.context, safeStatus))
            // Use the exact same source, precision and stale/offline rules as Dashboard.
            tvTemperature.text = UiUtils.zoneTemperatureText(z, controllerOnline, now)
            tvHumidity.text = UiUtils.zoneHumidityText(z, controllerOnline, now)
            val red = ContextCompat.getColor(root.context, R.color.tg_red)
            UiUtils.bindDetails(detailRows, listOf(
                DetailRow("Quality", z.quality, UiUtils.statusColor(root.context, z.quality)),
                DetailRow("Last reading", UiUtils.fullTime(z.lastUpdateMillis)),
                DetailRow("Reading age", UiUtils.ageText(UiUtils.zoneAgeMs(z, now))),
                DetailRow("Consecutive errors", z.consecutive_errors.toString(), red.takeIf { z.consecutive_errors > 0 }),
                DetailRow("Total errors", z.total_errors.toString()),
                DetailRow("Reconnects", z.reconnects.toString()),
                DetailRow("Last error time", UiUtils.epochSecondsTime(root.context, z.last_error_at)),
                DetailRow("Last error", z.error ?: "None", red.takeIf { z.error != null })
            ))
        }
    }
}
