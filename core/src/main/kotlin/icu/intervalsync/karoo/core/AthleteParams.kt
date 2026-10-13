package icu.intervalsync.karoo.core

/**
 * Rider's critical power model, the seed for every metric.
 *
 * @param cpW critical power in watts
 * @param wPrimeJ W′ (work capacity above CP) in joules
 */
data class AthleteParams(
    val cpW: Double,
    val wPrimeJ: Double,
) {
    init {
        require(cpW > 0) { "cpW must be positive" }
        require(wPrimeJ > 0) { "wPrimeJ must be positive" }
    }

    companion object {
        /** Placeholder W′ until the rider's own value is known. */
        const val DEFAULT_W_PRIME_J = 20_000.0
    }
}
