package icu.intervalsync.karoo.core

/** Metric values after a given second of the ride. */
data class RideSnapshot(
    /** Null when no CP/W′ is known for the rider. */
    val wPrimeBalanceJ: Double?,
    /** Remaining share of W′, 0..1. */
    val wPrimeFraction: Double?,
    val best5MinW: Double?,
    val best20MinW: Double?,
    val recordedSeconds: Long,
)

/**
 * All live metrics for one ride, advanced one second at a time.
 */
class RideTracker(params: AthleteParams?) {
    private var wPrime: WPrimeBalance? = params?.let { WPrimeBalance(it) }
    private val best5Min = RollingBest(300)
    private val best20Min = RollingBest(1200)
    private var recordedSeconds = 0L

    /**
     * Advance by one second.
     *
     * @param recording false while the ride is paused: W′ keeps recovering,
     * but the second doesn't count toward recorded time or the rolling bests.
     */
    fun tick(powerW: Double, recording: Boolean): RideSnapshot {
        wPrime?.update(powerW)
        if (recording) {
            best5Min.add(powerW)
            best20Min.add(powerW)
            recordedSeconds++
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

    fun snapshot(): RideSnapshot = RideSnapshot(
        wPrimeBalanceJ = wPrime?.balanceJ,
        wPrimeFraction = wPrime?.fraction,
        best5MinW = best5Min.bestW,
        best20MinW = best20Min.bestW,
        recordedSeconds = recordedSeconds,
    )
}
