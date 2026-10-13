package icu.intervalsync.karoo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RollingBestTest {
    @Test
    fun noBestUntilWindowIsFull() {
        val best = RollingBest(300)
        repeat(299) { assertNull(best.add(200.0)) }
        assertEquals(200.0, best.add(200.0))
    }

    @Test
    fun keepsBestAfterPowerDrops() {
        val best = RollingBest(10)
        repeat(10) { best.add(300.0) }
        repeat(50) { best.add(100.0) }
        assertEquals(300.0, best.bestW)
    }

    @Test
    fun findsBestWindowInsideRide() {
        val best = RollingBest(4)
        listOf(100, 100, 200, 400, 400, 300, 100, 100).forEach { best.add(it.toDouble()) }
        // best 4 s: 200, 400, 400, 300
        assertEquals(325.0, best.bestW)
    }
}

class RideTrackerTest {
    private val params = AthleteParams(cpW = 300.0, wPrimeJ = 20_000.0)

    @Test
    fun tracksBestsOverRecordedTime() {
        val tracker = RideTracker(params)
        repeat(300) { tracker.tick(250.0, recording = true) }
        val afterFive = tracker.snapshot()
        assertEquals(250.0, afterFive.best5MinW)
        assertNull(afterFive.best20MinW)
        repeat(900) { tracker.tick(250.0, recording = true) }
        assertEquals(250.0, tracker.snapshot().best20MinW)
        assertEquals(1200L, tracker.snapshot().recordedSeconds)
    }

    @Test
    fun pauseRecoversWPrimeButDoesNotCountTowardBests() {
        val tracker = RideTracker(params)
        repeat(299) { tracker.tick(400.0, recording = true) }
        val beforePause = assertNotNull(tracker.snapshot().wPrimeBalanceJ)
        repeat(600) { tracker.tick(0.0, recording = false) }
        val paused = tracker.snapshot()
        assertTrue(assertNotNull(paused.wPrimeBalanceJ) > beforePause)
        assertNull(paused.best5MinW)
        assertEquals(299L, paused.recordedSeconds)
        // the paused zeros never entered the window
        assertEquals(400.0, tracker.tick(400.0, recording = true).best5MinW)
    }

    @Test
    fun noWPrimeWithoutParams() {
        val tracker = RideTracker(null)
        val snapshot = tracker.tick(400.0, recording = true)
        assertNull(snapshot.wPrimeBalanceJ)
        assertNull(snapshot.wPrimeFraction)
        tracker.updateParams(params)
        assertEquals(20_000.0, tracker.snapshot().wPrimeBalanceJ)
    }
}
