package com.thermalguard.app.ui

import com.thermalguard.app.data.ServerClock
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.thermalguard.app.R
import androidx.core.content.ContextCompat
import com.thermalguard.app.databinding.ItemZoneBinding
import com.thermalguard.app.model.ZoneReading

class ZoneAdapter : ListAdapter<ZoneReading, ZoneAdapter.Holder>(Diff) {
    private var controllerOnline: Boolean = false
    private var warningLimit: Double = 35.0
    private var criticalLimit: Double = 38.0

    fun setThresholds(warning: Double, critical: Double) {
        warningLimit = warning
        criticalLimit = critical
    }

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
    class Holder(val b: ItemZoneBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemZoneBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val z = getItem(position)
        with(holder.b) {
            tvName.text = z.name.ifBlank { z.id.replaceFirstChar { it.uppercase() } }
            val now = ServerClock.now()
            val ageMs = UiUtils.zoneAgeMs(z, now)
            val stale = UiUtils.isZoneStale(z, now)
            val safeStatus = UiUtils.effectiveZoneStatus(z, controllerOnline, now)

            UiUtils.styleChip(chipStatus, safeStatus)
            tvTemperature.setTextColor(UiUtils.statusColor(root.context, safeStatus))
            tvTemperature.text = UiUtils.zoneTemperatureText(z, controllerOnline, now)
            tvHumidity.text = UiUtils.zoneHumidityText(z, controllerOnline, now)

            val offline = safeStatus.equals("OFFLINE", true)
            val rows = mutableListOf(
                DetailRow("Sensor quality", if (offline) "OFFLINE" else z.quality, UiUtils.statusColor(root.context, if (offline) "OFFLINE" else z.quality)),
                DetailRow("Last reading", UiUtils.fullTime(z.lastUpdateMillis)),
                DetailRow("Reading age", UiUtils.ageText(ageMs)),
                DetailRow("Warning limit", String.format(java.util.Locale.US, "%.1f °C", warningLimit)),
                DetailRow("Critical limit", String.format(java.util.Locale.US, "%.1f °C", criticalLimit)),
                DetailRow("Margin", if (offline) "--" else UiUtils.marginText(z.temperature, warningLimit, criticalLimit))
            )
            z.error?.let { rows += DetailRow("Sensor error", it, ContextCompat.getColor(root.context, R.color.tg_red)) }
            UiUtils.bindDetails(detailRows, rows)

            tvUpdated.text = when {
                !controllerOnline -> "Raspberry Pi offline • last sensor update ${if (z.lastUpdateMillis > 0L) "${formatAge(ageMs)} ago" else "unavailable"}"
                z.lastUpdateMillis <= 0L -> "Last updated: unavailable"
                stale -> "Last updated: stale (${formatAge(ageMs)} ago)"
                else -> "Live • updated ${formatAge(ageMs)} ago"
            }
        }
    }
    private fun formatAge(ms: Long): String {
        val seconds = (ms / 1000L).coerceAtLeast(0L)
        return when {
            seconds < 2 -> "now"
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m"
            else -> "${seconds / 3600}h"
        }
    }

}
