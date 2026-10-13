package icu.intervalsync.karoo.core

/**
 * Best rolling average power over a fixed window of 1 Hz samples.
 *
 * No best exists until a full window has been recorded.
 */
class RollingBest(val windowSeconds: Int) {
    init {
        require(windowSeconds > 0) { "windowSeconds must be positive" }
    }

    private val samples = DoubleArray(windowSeconds)
    private var count = 0L
    private var sum = 0.0

    var bestW: Double? = null
        private set

    fun add(powerW: Double): Double? {
        val index = (count % windowSeconds).toInt()
        if (count >= windowSeconds) sum -= samples[index]
        samples[index] = powerW
        sum += powerW
        count++
        if (count >= windowSeconds) {
            val average = sum / windowSeconds
            val best = bestW
            if (best == null || average > best) bestW = average
        }
        return bestW
    }
}
