package com.thermalguard.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.thermalguard.app.R
import com.thermalguard.app.databinding.ItemAlertBinding
import com.thermalguard.app.model.AlertItem

class AlertAdapter(
    /** Long-press delete handler; null when the signed-in user may not delete alerts (staff). */
    private val onLongPress: ((AlertItem) -> Unit)?
) : ListAdapter<AlertItem, AlertAdapter.Holder>(Diff) {
    object Diff : DiffUtil.ItemCallback<AlertItem>() {
        override fun areItemsTheSame(a: AlertItem, b: AlertItem) =
            a.sourcePath == b.sourcePath && a.key == b.key
        override fun areContentsTheSame(a: AlertItem, b: AlertItem) = a == b
    }

    class Holder(val b: ItemAlertBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemAlertBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val a = getItem(position)
        val type = a.type.ifBlank { if (a.severity == "CRITICAL") "CRITICAL_TEMP" else "SYSTEM" }
        val colorRes = when {
            type.contains("RECOVER", true) -> R.color.tg_green
            type.contains("WARNING", true) -> R.color.tg_amber
            type.contains("OFFLINE", true) || type.contains("CRITICAL", true) -> R.color.tg_red
            else -> R.color.tg_blue_2
        }
        with(holder.b) {
            val color = ContextCompat.getColor(root.context, colorRes)
            tvType.text = type.replace("_", " ").uppercase()
            tvType.setTextColor(color)
            ivIcon.setColorFilter(color)
            val label = a.name.ifBlank { a.zone.replaceFirstChar { it.uppercase() } }
            tvMessage.text = when {
                a.message.isNotBlank() -> a.message
                a.temperature != null -> "$label temperature: %.1f °C".format(a.temperature)
                else -> type.replace("_", " ")
            }
            val rows = mutableListOf(
                DetailRow("Severity", a.severity.ifBlank { "--" }.uppercase(), color),
                DetailRow("Location", label.ifBlank { "System" }),
                DetailRow("Source", if (a.sourcePath == "system_alerts") "System alert" else "Temperature alert")
            )
            a.temperature?.let { rows += DetailRow("Temperature", String.format(java.util.Locale.US, "%.2f °C", it)) }
            a.humidity?.let { rows += DetailRow("Humidity", String.format(java.util.Locale.US, "%.2f %%", it)) }
            rows += DetailRow("Time", if (a.timestampMillis > 0L) UiUtils.fullTime(a.timestampMillis) else UiUtils.fullTime(a.timestamp))
            UiUtils.bindDetails(detailRows, rows)
            val deleteHandler = onLongPress
            if (deleteHandler != null) {
                root.isLongClickable = true
                root.setOnLongClickListener {
                    deleteHandler(a)
                    true
                }
            } else {
                root.setOnLongClickListener(null)
                root.isLongClickable = false
            }
        }
    }
}
