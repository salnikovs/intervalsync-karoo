package icu.intervalsync.karoo

import android.content.Context
import android.content.SharedPreferences
import icu.intervalsync.karoo.account.number
import icu.intervalsync.karoo.account.string
import icu.intervalsync.karoo.core.AthleteParams
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/** One training zone as set up in Intervals.icu; [max] is null for the open-ended top zone. */
data class Zone(val name: String, val max: Int?)

/**
 * What the rider set up in Intervals.icu, as last synced through Intervalsync.
 * Everything is null or empty until the Karoo is connected.
 */
data class AthleteSettings(
    val syncedCpW: Int? = null,
    val syncedWPrimeJ: Int? = null,
    val syncedFtpW: Int? = null,
    /** Upper bounds in watts. */
    val powerZones: List<Zone> = emptyList(),
    /** Upper bounds in beats per minute. */
    val hrZones: List<Zone> = emptyList(),
) {
    /** FTP for the power zones: Intervals.icu's, else the Karoo profile's. */
    fun resolveFtp(karooFtpW: Int?): Int? = syncedFtpW ?: karooFtpW?.takeIf { it > 0 }

    /** Intervals.icu's CP and W′; failing those CP falls back to FTP and W′ to [AthleteParams.DEFAULT_W_PRIME_J]. */
    fun resolve(karooFtpW: Int?): AthleteParams? {
        val cp = syncedCpW ?: resolveFtp(karooFtpW) ?: return null
        return AthleteParams(cp.toDouble(), syncedWPrimeJ?.toDouble() ?: AthleteParams.DEFAULT_W_PRIME_J)
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("athlete", Context.MODE_PRIVATE)

    fun read(): AthleteSettings = AthleteSettings(
        syncedCpW = positive(KEY_SYNCED_CP_W),
        syncedWPrimeJ = positive(KEY_SYNCED_W_PRIME_J),
        syncedFtpW = positive(KEY_SYNCED_FTP_W),
        powerZones = zones(KEY_POWER_ZONES, "max_w"),
        hrZones = zones(KEY_HR_ZONES, "max_bpm"),
    )

    /**
     * Save what was pulled from Intervalsync. The zone arrays are kept as the JSON the
     * service sent (see docs/device-api.md); null clears them.
     */
    fun writeSynced(cpW: Int?, wPrimeJ: Int?, ftpW: Int?, powerZones: JsonArray?, hrZones: JsonArray?) {
        prefs.edit()
            .putInt(KEY_SYNCED_CP_W, cpW ?: 0)
            .putInt(KEY_SYNCED_W_PRIME_J, wPrimeJ ?: 0)
            .putInt(KEY_SYNCED_FTP_W, ftpW ?: 0)
            .putString(KEY_POWER_ZONES, powerZones?.toString())
            .putString(KEY_HR_ZONES, hrZones?.toString())
            .apply()
    }

    /** How many weeks the trend charts show: 6 or 12. */
    var trendWeeks: Int
        get() = prefs.getInt(KEY_TREND_WEEKS, 6)
        set(value) = prefs.edit().putInt(KEY_TREND_WEEKS, value).apply()

    /** Current settings, then again on every change. */
    fun flow(): Flow<AthleteSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun positive(key: String): Int? = prefs.getInt(key, 0).takeIf { it > 0 }

    private fun zones(key: String, maxKey: String): List<Zone> {
        val stored = prefs.getString(key, null) ?: return emptyList()
        val array = runCatching { Json.parseToJsonElement(stored) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val zone = element as? JsonObject ?: return@mapNotNull null
            Zone(zone.string("name").orEmpty(), zone.number(maxKey)?.roundToInt())
        }
    }

    companion object {
        private const val KEY_SYNCED_CP_W = "synced_cp_w"
        private const val KEY_SYNCED_W_PRIME_J = "synced_w_prime_j"
        private const val KEY_SYNCED_FTP_W = "synced_ftp_w"
        private const val KEY_POWER_ZONES = "power_zones"
        private const val KEY_HR_ZONES = "hr_zones"
        private const val KEY_TREND_WEEKS = "trend_weeks"
    }
}
