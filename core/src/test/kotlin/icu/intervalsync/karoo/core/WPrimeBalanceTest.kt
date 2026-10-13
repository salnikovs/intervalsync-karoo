package icu.intervalsync.karoo.core

import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WPrimeBalanceTest {
    private val params = AthleteParams(cpW = 300.0, wPrimeJ = 20_000.0)

    @Test
    fun startsFull() {
        val wbal = WPrimeBalance(params)
        assertEquals(20_000.0, wbal.balanceJ)
        assertEquals(1.0, wbal.fraction)
    }

    @Test
    fun drainsByWorkAboveCp() {
        val wbal = WPrimeBalance(params)
        repeat(60) { wbal.update(400.0) }
        assertEquals(14_000.0, wbal.balanceJ, 1e-6)
        assertEquals(0.7, wbal.fraction, 1e-9)
    }

    @Test
    fun emptyAfterWPrimeOverPowerAboveCp() {
        val wbal = WPrimeBalance(params)
        // 20 kJ at 100 W above CP lasts 200 s
        repeat(200) { wbal.update(400.0) }
        assertEquals(0.0, wbal.balanceJ, 1e-6)
    }

    @Test
    fun holdsAtCp() {
        val wbal = WPrimeBalance(params)
        repeat(60) { wbal.update(400.0) }
        repeat(600) { wbal.update(300.0) }
        assertEquals(14_000.0, wbal.balanceJ, 1e-6)
    }

    @Test
    fun recoversExponentiallyBelowCp() {
        val wbal = WPrimeBalance(params)
        repeat(100) { wbal.update(400.0) } // 10 kJ spent
        repeat(120) { wbal.update(100.0) }
        // deficit decays with rate (CP - P) / W′ = 200 / 20000 per second
        val expected = 20_000.0 - 10_000.0 * exp(-200.0 / 20_000.0 * 120)
        assertEquals(expected, wbal.balanceJ, 1e-6)
    }

    @Test
    fun recoversFasterFurtherBelowCp() {
        val easy = WPrimeBalance(params)
        val easier = WPrimeBalance(params)
        repeat(100) {
            easy.update(400.0)
            easier.update(400.0)
        }
        repeat(60) {
            easy.update(250.0)
            easier.update(0.0)
        }
        assertTrue(easier.balanceJ > easy.balanceJ)
    }

    @Test
    fun goesNegativeButFractionClamps() {
        val wbal = WPrimeBalance(params)
        repeat(250) { wbal.update(400.0) }
        assertEquals(-5_000.0, wbal.balanceJ, 1e-6)
        assertEquals(-5_000.0, wbal.minBalanceJ, 1e-6)
        assertEquals(0.0, wbal.fraction)
    }

    @Test
    fun updateParamsKeepsJoulesSpent() {
        val wbal = WPrimeBalance(params)
        repeat(60) { wbal.update(400.0) } // 6 kJ spent
        wbal.updateParams(AthleteParams(cpW = 310.0, wPrimeJ = 22_000.0))
        assertEquals(16_000.0, wbal.balanceJ, 1e-6)
    }
}
