package com.thermalguard.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone alarm must follow the Raspberry Pi's alarm state (audit items 1, 2 and 5). */
class TemperatureAlertEvaluatorTest {
    private val now = 1_800_000_000_000L

    private fun system(lastSeenMs: Long = now - 2_000L, status: String = "ONLINE") = LiveSystem(
        warning_temp_c = 35.0,
        critical_temp_c = 38.0,
        controller_status = status,
        controller_last_seen_ms = lastSeenMs
    )

    private fun zone(id: String, temp: Double?, status: String, alarm: String = "", quality: String = "GOOD") =
        ZoneReading(
            id = id, name = id.replaceFirstChar { it.uppercase() }, temperature = temp,
            status = status, quality = quality, alarmState = alarm, lastUpdateMillis = now - 3_000L
        )

    private fun eval(vararg zones: ZoneReading, sys: LiveSystem = system()) =
        TemperatureAlertEvaluator.evaluate(sys, zones.toList(), now)

    @Test
    fun followsPiCriticalWithTemperature() {
        val s = eval(zone("entrance", 31.0, "NORMAL", "NORMAL"), zone("front", 39.2, "CRITICAL", "CRITICAL"))
        assertEquals(TemperatureAlertLevel.CRITICAL, s.level)
        assertEquals(39.2, s.temperatureC!!, 0.001)
        assertEquals("Front", s.zoneName)
        assertFalse(s.sensorOffline)
    }

    @Test
    fun offlineSensorHoldingCriticalStillAlarms() {
        // Audit #1: the hot zone's sensor drops out; the Pi holds CRITICAL (siren on).
        val s = eval(zone("front", null, "OFFLINE", "CRITICAL", "OFFLINE"), zone("exit", 30.0, "NORMAL", "NORMAL"))
        assertEquals(TemperatureAlertLevel.CRITICAL, s.level)
        assertTrue(s.sensorOffline)
        assertNull(s.temperatureC)
        assertEquals("Front", s.zoneName)
    }

    @Test
    fun noFlappingAtWarningThreshold() {
        // Audit #1: 34.9 C used to clear the alarm (and the user's mute) on every dip.
        // The Pi keeps WARNING until 3 readings below 34.5 C, so the phone does too.
        for (t in listOf(35.0, 34.9, 35.0, 34.9, 34.8)) {
            assertEquals("at $t", TemperatureAlertLevel.WARNING, eval(zone("front", t, "WARNING", "WARNING")).level)
        }
    }

    @Test
    fun singleHotReadingDoesNotJumpToCritical() {
        // Pi needs 3 confirmed readings for CRITICAL; one 38.4 C sample is still WARNING.
        assertEquals(TemperatureAlertLevel.WARNING, eval(zone("front", 38.4, "WARNING", "WARNING")).level)
    }

    @Test
    fun normalWhenPiSaysNormal() {
        val s = eval(zone("entrance", 33.0, "NORMAL", "NORMAL"), zone("front", null, "OFFLINE", "NORMAL", "OFFLINE"))
        assertEquals(TemperatureAlertLevel.NORMAL, s.level)
        assertFalse(s.controllerLost)
    }

    @Test
    fun highestLevelWinsThenHottest() {
        val s = eval(
            zone("entrance", 36.5, "WARNING", "WARNING"),
            zone("exit", 38.1, "CRITICAL", "CRITICAL"),
            zone("front", 39.0, "CRITICAL", "CRITICAL")
        )
        assertEquals(TemperatureAlertLevel.CRITICAL, s.level)
        assertEquals("Front", s.zoneName)
    }

    @Test
    fun olderPiWithoutAlarmStateUsesStatus() {
        assertEquals(TemperatureAlertLevel.CRITICAL, eval(zone("front", 39.0, "CRITICAL")).level)
    }

    @Test
    fun lostHeartbeatIsReportedNotTreatedAsSafe() {
        // Audit #2: a silent Pi used to look like NORMAL and clear the alarm.
        val s = eval(zone("front", 39.0, "CRITICAL", "CRITICAL"), sys = system(lastSeenMs = now - 60_000L))
        assertTrue(s.controllerLost)
    }

    @Test
    fun cleanShutdownMarkerIsLostContact() {
        assertTrue(eval(zone("front", 30.0, "NORMAL"), sys = system(status = "OFFLINE")).controllerLost)
    }

    @Test
    fun heartbeatAgeUsesGivenServerTime() {
        // Audit #5: freshness is measured on one (server) clock passed in by the caller.
        assertFalse(ControllerHealth.isOnline(system(lastSeenMs = now - 16_000L), now))
        assertTrue(ControllerHealth.isOnline(system(lastSeenMs = now - 14_000L), now))
    }
}
