package com.thermalguard.app.ui

import com.thermalguard.app.model.LiveSystem
import org.junit.Assert.assertEquals
import org.junit.Test

class SirenModeTextTest {
    private fun sys(mode: String, left: Long?) = LiveSystem(relay_mode = mode, relay_off_remaining_seconds = left)

    @Test
    fun offCountdownRoundsUpAndNeverShowsZero() {
        assertEquals("OFF • AUTO in 30 min", SirenConfirm.modeText(sys("off", 1799)))
        assertEquals("OFF • AUTO in 23 min", SirenConfirm.modeText(sys("off", 1321)))
        assertEquals("OFF • AUTO in 1 min", SirenConfirm.modeText(sys("off", 5)))
        assertEquals("OFF • AUTO in 1 min", SirenConfirm.modeText(sys("off", 0)))
    }

    @Test
    fun otherModesAndOffWithoutTimer() {
        assertEquals("AUTO", SirenConfirm.modeText(sys("auto", null)))
        assertEquals("ON", SirenConfirm.modeText(sys("on", null)))
        assertEquals("OFF", SirenConfirm.modeText(sys("off", null)))   // revert disabled on the Pi
    }
}
