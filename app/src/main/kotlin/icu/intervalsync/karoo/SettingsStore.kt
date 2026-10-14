package icu.intervalsync.karoo

import android.content.Context
import android.content.SharedPreferences
import icu.intervalsync.karoo.core.AthleteParams
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Rider's CP, W′ and FTP: typed in on the Karoo ([cpW], [wPrimeJ], [ftpW]) or synced
 * from Intervalsync ([syncedCpW], [syncedWPrimeJ], [syncedFtpW]). Null means "not set".
 */
data class AthleteSettings(
    val cpW: Int? = null,
    val wPrimeJ: Int? = null,
    val ftpW: Int? = null,
    val syncedCpW: Int? = null,
    val syncedWPrimeJ: Int? = null,
    val syncedFtpW: Int? = null,
) {
    /** FTP for the power zones: typed in, then Intervalsync's, then the Karoo profile's. */
    fun resolveFtp(karooFtpW: Int?): Int? = ftpW ?: syncedFtpW ?: karooFtpW?.takeIf { it > 0 }

    /** Typed-in values win, then Intervalsync's; failing those CP falls back to FTP and W′ to [AthleteParams.DEFAULT_W_PRIME_J]. */
    fun resolve(karooFtpW: Int?): AthleteParams? {
        val cp = cpW ?: syncedCpW ?: resolveFtp(karooFtpW) ?: return null
        val wPrime = wPrimeJ ?: syncedWPrimeJ
        return AthleteParams(cp.toDouble(), wPrime?.toDouble() ?: AthleteParams.DEFAULT_W_PRIME_J)
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("athlete", Context.MODE_PRIVATE)

    fun read(): AthleteSettings = AthleteSettings(
        cpW = positive(KEY_CP_W),
        wPrimeJ = positive(KEY_W_PRIME_J),
        ftpW = positive(KEY_FTP_W),
        syncedCpW = positive(KEY_SYNCED_CP_W),
        syncedWPrimeJ = positive(KEY_SYNCED_W_PRIME_J),
        syncedFtpW = positive(KEY_SYNCED_FTP_W),
    )

    /** Save the values typed in on the Karoo. */
    fun write(cpW: Int?, wPrimeJ: Int?, ftpW: Int?) {
        prefs.edit()
            .putInt(KEY_CP_W, cpW ?: 0)
            .putInt(KEY_W_PRIME_J, wPrimeJ ?: 0)
            .putInt(KEY_FTP_W, ftpW ?: 0)
            .apply()
    }

    /** Save the values pulled from Intervalsync. */
    fun writeSynced(cpW: Int?, wPrimeJ: Int?, ftpW: Int?) {
        prefs.edit()
            .putInt(KEY_SYNCED_CP_W, cpW ?: 0)
            .putInt(KEY_SYNCED_W_PRIME_J, wPrimeJ ?: 0)
            .putInt(KEY_SYNCED_FTP_W, ftpW ?: 0)
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

    companion object {
        private const val KEY_CP_W = "cp_w"
        private const val KEY_W_PRIME_J = "w_prime_j"
        private const val KEY_FTP_W = "ftp_w"
        private const val KEY_TREND_WEEKS = "trend_weeks"
        private const val KEY_SYNCED_CP_W = "synced_cp_w"
        private const val KEY_SYNCED_W_PRIME_J = "synced_w_prime_j"
        private const val KEY_SYNCED_FTP_W = "synced_ftp_w"
    }
}
