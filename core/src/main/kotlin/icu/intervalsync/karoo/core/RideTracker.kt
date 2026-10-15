package icu.intervalsync.karoo.core

/** Metric values after a given second of the ride. */
data class RideSnapshot(
    /** Null when no CP/W′ is known for the rider. */
    val wPrimeBalanceJ: Double?,
    /** Remaining share of W′, 0..1. */
    val wPrimeFraction: Double?,
    /** Lowest W′ balance so far, in joules. */
    val wPrimeMinJ: Double?,
    /** The rider's full W′, in joules. */
    val wPrimeCapacityJ: Double?,
    val best5MinW: Double?,
    val best20MinW: Double?,
    /** Null until a second has been recorded. */
    val averagePowerW: Double?,
    /** Stress per power zone, lowest zone first; null when the rider's FTP or zones aren't known. */
    val zoneStress: List<Double>?,
    val recordedSeconds: Long,
    /** Best 5 s, 15 s, 30 s and 1 min average power so far. */
    val best5sW: Double? = null,
    val best15sW: Double? = null,
    val best30sW: Double? = null,
    val best1MinW: Double? = null,
)

/**
 * All live metrics for one ride, advanced one second at a time.
 */
class RideTracker(params: AthleteParams?, ftpW: Double? = null, zoneMaxW: List<Double?>? = null) {
    private var wPrime: WPrimeBalance? = params?.let { WPrimeBalance(it) }
    private val zones: ZoneStress? =
        if (ftpW != null && ftpW > 0 && !zoneMaxW.isNullOrEmpty()) ZoneStress(ftpW, zoneMaxW) else null
    private val best5s = RollingBest(5)
    private val best15s = RollingBest(15)
    private val best30s = RollingBest(30)
    private val best1Min = RollingBest(60)
    private val best5Min = RollingBest(300)
    private val best20Min = RollingBest(1200)
    private var recordedSeconds = 0L
    private var recordedJoules = 0.0

    /**
     * Advance by one second.
     *
     * @param recording false while the ride is paused: W′ keeps recovering,
     * but the second doesn't count toward recorded time, the rolling bests or zone stress.
     */
    fun tick(powerW: Double, recording: Boolean): RideSnapshot {
        wPrime?.update(powerW)
        if (recording) {
            best5s.add(powerW)
            best15s.add(powerW)
            best30s.add(powerW)
            best1Min.add(powerW)
            best5Min.add(powerW)
            best20Min.add(powerW)
            zones?.add(powerW)
            recordedSeconds++
            recordedJoules += powerW
        }
        return snapshot()
    }

    fun updateParams(params: AthleteParams?) {
        val current = wPrime
        wPrime = when {
            params == null -> null
            current == null -> WPrimeBalance(params)
            else -> current.also { it.updateParams(params) }
        }
    }

    companion object {
        /**
         * Metrics for a finished ride from its 1 Hz power. A recorded ride has no paused
         * seconds, so W′ gets no recovery for stops.
         */
        fun summarize(powerW: List<Double>, params: AthleteParams?, ftpW: Double?, zoneMaxW: List<Double?>?): RideSnapshot {
            val tracker = RideTracker(params, ftpW, zoneMaxW)
            powerW.forEach { tracker.tick(it, recording = true) }
            return tracker.snapshot()
        }
    }

    fun snapshot(): RideSnapshot = RideSnapshot(
        wPrimeBalanceJ = wPrime?.balanceJ,
        wPrimeFraction = wPrime?.fraction,
        wPrimeMinJ = wPrime?.minBalanceJ,
        wPrimeCapacityJ = wPrime?.params?.wPrimeJ,
        best5MinW = best5Min.bestW,
        best20MinW = best20Min.bestW,
        averagePowerW = if (recordedSeconds > 0) recordedJoules / recordedSeconds else null,
        zoneStress = zones?.byZone(),
        recordedSeconds = recordedSeconds,
        best5sW = best5s.bestW,
        best15sW = best15s.bestW,
        best30sW = best30s.bestW,
        best1MinW = best1Min.bestW,
    )
}
