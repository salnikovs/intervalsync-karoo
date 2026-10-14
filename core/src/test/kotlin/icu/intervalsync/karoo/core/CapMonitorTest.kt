package icu.intervalsync.karoo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapMonitorTest {
    @Test
    fun warnsOnceTheAverageOverTheWindowIsAboveTheCap() {
        val monitor = CapMonitor(windowSeconds = 10, cooldownSeconds = 120)
        repeat(9) { assertFalse(monitor.tick(250.0, cap = 200.0)) }
        assertTrue(monitor.tick(250.0, cap = 200.0))
    }

    @Test
    fun aShortSurgeDoesNotWarn() {
        val monitor = CapMonitor(windowSeconds = 10, cooldownSeconds = 120)
        repeat(60) { assertFalse(monitor.tick(180.0, cap = 200.0)) }
        // 3 s at 250 W lifts the 10 s average to 201 W only if the rest is at 180 W: (7*180 + 3*250)/10 = 201
        repeat(2) { assertFalse(monitor.tick(250.0, cap = 200.0)) }
    }

    @Test
    fun staysQuietForTheCooldownThenWarnsAgain() {
        val monitor = CapMonitor(windowSeconds = 10, cooldownSeconds = 120)
        val warnings = (1..300).filter { monitor.tick(250.0, cap = 200.0) }
        assertEquals(listOf(10, 130, 250), warnings)
    }

    @Test
    fun noCapOrNoValueNeverWarnsAndRestartsTheAverage() {
        val monitor = CapMonitor(windowSeconds = 10, cooldownSeconds = 120)
        repeat(30) { assertFalse(monitor.tick(250.0, cap = null)) }
        repeat(9) { monitor.tick(250.0, cap = 200.0) }
        assertFalse(monitor.tick(null, cap = 200.0))
        repeat(9) { assertFalse(monitor.tick(250.0, cap = 200.0)) }
        assertTrue(monitor.tick(250.0, cap = 200.0))
    }
}
