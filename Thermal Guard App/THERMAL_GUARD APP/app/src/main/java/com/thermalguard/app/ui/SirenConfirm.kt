package com.thermalguard.app.ui

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.thermalguard.app.model.LiveSystem
import kotlin.math.roundToInt

/**
 * Confirmation before a manual siren ON or OFF (same wording as the kiosk).
 *
 * ON sounds the siren even when temperatures are normal; OFF silences it even
 * at CRITICAL (until the Raspberry Pi's auto-revert). A mistaken tap on either
 * matters, so both ask first. AUTO - the safe default - is applied at once.
 */
object SirenConfirm {
    fun ask(
        context: Context,
        mode: String,
        system: LiveSystem?,
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ) {
        if (mode != "on" && mode != "off") {
            onConfirm()
            return
        }
        val minutes = (system?.relay_off_revert_minutes ?: 30.0).roundToInt()
        val (title, body, button) = if (mode == "on") {
            Triple(
                "Turn the siren ON?",
                "The siren will sound continuously until someone sets it back to AUTO or OFF, " +
                    "even if temperatures are normal.",
                "Turn siren ON"
            )
        } else {
            Triple(
                "Turn the siren OFF?",
                if (minutes > 0) {
                    "The siren will stay silent for $minutes minutes, then return to AUTO. " +
                        "It returns to AUTO at once if any zone newly reaches CRITICAL temperature."
                } else {
                    "The siren will stay silent even if a zone reaches CRITICAL temperature, " +
                        "until someone sets it back to AUTO."
                },
                "Turn siren OFF"
            )
        }
        var answered = false
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(body)
            .setNegativeButton("Cancel") { _, _ -> answered = true; onCancel() }
            .setPositiveButton(button) { _, _ -> answered = true; onConfirm() }
            .setOnDismissListener { if (!answered) onCancel() }   // back button / tap outside
            .show()
    }

    /** "OFF • AUTO in 23 min" while a manual OFF is counting down, else the mode. */
    fun modeText(system: LiveSystem): String {
        val left = system.relay_off_remaining_seconds
        return if (system.relay_mode == "off" && left != null) {
            "OFF • AUTO in ${((left + 59) / 60).coerceAtLeast(1)} min"
        } else {
            system.relay_mode.uppercase()
        }
    }
}
