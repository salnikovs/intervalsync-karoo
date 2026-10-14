package icu.intervalsync.karoo.core

/**
 * Watches one stream against a cap the rider was given for the day, and says when to
 * warn them: once the average over the last [windowSeconds] is above the cap, and then
 * not again for [cooldownSeconds].
 */
class CapMonitor(private val windowSeconds: Int = 10, private val cooldownSeconds: Int = 120) {
    private val window = ArrayDeque<Double>()
    private var sum = 0.0
    private var sinceWarning = cooldownSeconds

    /** Advance by one second. Returns true when the rider should be warned now. */
    fun tick(value: Double?, cap: Double?): Boolean {
        if (sinceWarning < cooldownSeconds) sinceWarning++
        if (value == null || cap == null) {
            // A dropout or no cap: start the average again rather than bridge the gap
            window.clear()
            sum = 0.0
            return false
        }
        window.addLast(value)
        sum += value
        if (window.size > windowSeconds) sum -= window.removeFirst()
        if (window.size == windowSeconds && sum / windowSeconds > cap && sinceWarning >= cooldownSeconds) {
            sinceWarning = 0
            return true
        }
        return false
    }
}
