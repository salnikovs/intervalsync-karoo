package icu.intervalsync.karoo.core

/** Coggan power zones, each up to and including [upperFtpFraction] of FTP. */
enum class PowerZone(val upperFtpFraction: Double) {
    RECOVERY(0.55),
    ENDURANCE(0.75),
    TEMPO(0.90),
    THRESHOLD(1.05),
    VO2MAX(1.20),
    ANAEROBIC(Double.POSITIVE_INFINITY),
    ;

    companion object {
        fun of(powerW: Double, ftpW: Double): PowerZone {
            val fraction = powerW / ftpW
            return entries.first { fraction <= it.upperFtpFraction }
        }
    }
}

/**
 * Training stress split by power zone: each second adds (P / FTP)² / 36 to its zone.
 *
 * The shares show where a ride's stress came from. The total runs below a
 * normalized-power load, most of all on rides with sprints.
 */
class ZoneStress(private val ftpW: Double) {
    init {
        require(ftpW > 0) { "ftpW must be positive" }
    }

    private val stress = DoubleArray(PowerZone.entries.size)

    fun add(powerW: Double) {
        val fraction = powerW / ftpW
        stress[PowerZone.of(powerW, ftpW).ordinal] += fraction * fraction / 36
    }

    fun byZone(): Map<PowerZone, Double> = PowerZone.entries.associateWith { stress[it.ordinal] }
}
