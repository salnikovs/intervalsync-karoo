package icu.intervalsync.karoo.core

/**
 * Training stress split by the rider's own power zones: each second adds (P / FTP)² / 36
 * to the zone its power falls in.
 *
 * The shares show where a ride's stress came from. The total runs below a
 * normalized-power load, most of all on rides with sprints.
 *
 * @param zoneMaxW each zone's upper bound in watts, lowest zone first; null for an open-ended top zone
 */
class ZoneStress(private val ftpW: Double, private val zoneMaxW: List<Double?>) {
    init {
        require(ftpW > 0) { "ftpW must be positive" }
        require(zoneMaxW.isNotEmpty()) { "at least one zone is needed" }
    }

    private val stress = DoubleArray(zoneMaxW.size)

    /** Index of the zone [powerW] falls in; power above every bound counts in the top zone. */
    fun zoneOf(powerW: Double): Int {
        val index = zoneMaxW.indexOfFirst { it == null || powerW <= it }
        return if (index >= 0) index else zoneMaxW.lastIndex
    }

    fun add(powerW: Double) {
        val fraction = powerW / ftpW
        stress[zoneOf(powerW)] += fraction * fraction / 36
    }

    /** Stress per zone, in the order the zones were given. */
    fun byZone(): List<Double> = stress.toList()
}
