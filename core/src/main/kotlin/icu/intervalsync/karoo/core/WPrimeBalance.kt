package icu.intervalsync.karoo.core

import kotlin.math.exp

/**
 * W′ balance from Skiba's differential model.
 *
 * Above CP, W′ drains by the work done above CP. At or below CP it recharges
 * toward full, faster the further below CP the rider is:
 *
 *     dW′bal/dt = -(P - CP)                      P > CP
 *     dW′bal/dt = (W′ - W′bal) · (CP - P) / W′   P ≤ CP
 *
 * The recovery branch is integrated exactly over each step, so the step length
 * doesn't affect the result while power is constant.
 */
class WPrimeBalance(params: AthleteParams) {
    var params: AthleteParams = params
        private set

    /** Joules remaining. Goes negative when the rider outperforms the CP/W′ model. */
    var balanceJ: Double = params.wPrimeJ
        private set

    /** Lowest balance seen so far, in joules. */
    var minBalanceJ: Double = params.wPrimeJ
        private set

    /** Remaining share of W′, clamped to 0..1. */
    val fraction: Double
        get() = (balanceJ / params.wPrimeJ).coerceIn(0.0, 1.0)

    fun update(powerW: Double, dtSeconds: Double = 1.0): Double {
        val cp = params.cpW
        val wPrime = params.wPrimeJ
        balanceJ = if (powerW > cp) {
            balanceJ - (powerW - cp) * dtSeconds
        } else {
            wPrime - (wPrime - balanceJ) * exp(-(cp - powerW) / wPrime * dtSeconds)
        }
        if (balanceJ < minBalanceJ) minBalanceJ = balanceJ
        return balanceJ
    }

    /** Swap in new CP/W′ mid-ride, keeping the joules already spent. */
    fun updateParams(newParams: AthleteParams) {
        val delta = newParams.wPrimeJ - params.wPrimeJ
        balanceJ += delta
        minBalanceJ += delta
        params = newParams
    }
}
