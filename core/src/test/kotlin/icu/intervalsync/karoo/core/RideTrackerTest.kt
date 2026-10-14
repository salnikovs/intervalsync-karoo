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

class ZoneStressTest {
    // Seven zones as Intervals.icu sets them up for a 200 W FTP: 55, 75, 90, 105, 120, 150 % and an open top
    private val zones = listOf(110.0, 150.0, 180.0, 210.0, 240.0, 300.0, null)

    @Test
    fun zoneBoundariesBelongToTheLowerZone() {
        val stress = ZoneStress(200.0, zones)
        assertEquals(0, stress.zoneOf(110.0))
        assertEquals(1, stress.zoneOf(111.0))
        assertEquals(3, stress.zoneOf(210.0))
        assertEquals(6, stress.zoneOf(600.0))
    }

    @Test
    fun powerAboveEveryBoundCountsInTheTopZone() {
        assertEquals(1, ZoneStress(200.0, listOf(110.0, 150.0)).zoneOf(400.0))
    }

    @Test
    fun anHourAtFtpIsOneHundredInItsZone() {
        val stress = ZoneStress(200.0, zones)
        repeat(3600) { stress.add(200.0) }
        assertEquals(100.0, stress.byZone()[3], 1e-9)
        assertEquals(100.0, stress.byZone().sum(), 1e-9)
    }

    @Test
    fun trackerSplitsRecordedSecondsByZoneAndAveragesPower() {
        val tracker = RideTracker(AthleteParams(cpW = 240.0, wPrimeJ = 20_000.0), ftpW = 250.0, zoneMaxW = listOf(137.0, 187.0, 225.0, 262.0, 300.0, null))
        repeat(600) { tracker.tick(150.0, recording = true) }
        repeat(600) { tracker.tick(300.0, recording = true) }
        repeat(600) { tracker.tick(0.0, recording = false) }
        val snapshot = tracker.snapshot()
        assertEquals(225.0, snapshot.averagePowerW)
        val stress = assertNotNull(snapshot.zoneStress)
        assertEquals(600 * 0.36 / 36, stress[1], 1e-9)
        assertEquals(600 * 1.44 / 36, stress[4], 1e-9)
        assertEquals(0.0, stress[0])
        // 600 s at 60 W above CP drains W′ by 36 kJ
        assertEquals(20_000.0 - 36_000.0, assertNotNull(snapshot.wPrimeMinJ), 1e-6)
        assertEquals(20_000.0, snapshot.wPrimeCapacityJ)
    }

    @Test
    fun noZoneStressWithoutTheRidersZones() {
        assertNull(RideTracker(null).tick(200.0, recording = true).zoneStress)
        assertNull(RideTracker(null, ftpW = 250.0).tick(200.0, recording = true).zoneStress)
    }
}

class SummarizeTest {
    @Test
    fun aRecordedRideSummarizesLikeTheSameRideLive() {
        val params = AthleteParams(cpW = 240.0, wPrimeJ = 20_000.0)
        val zones = listOf(137.0, 187.0, 225.0, 262.0, 300.0, null)
        val power = List(900) { 150.0 } + List(300) { 300.0 } + List(900) { 150.0 }
        val live = RideTracker(params, ftpW = 250.0, zoneMaxW = zones)
        power.forEach { live.tick(it, recording = true) }
        assertEquals(live.snapshot(), RideTracker.summarize(power, params, ftpW = 250.0, zoneMaxW = zones))
        assertEquals(300.0, RideTracker.summarize(power, params, ftpW = 250.0, zoneMaxW = zones).best5MinW)
    }
}
