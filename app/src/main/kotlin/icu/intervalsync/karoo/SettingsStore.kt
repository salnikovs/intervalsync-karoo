package icu.intervalsync.karoo

import android.content.Context
import android.content.SharedPreferences
import icu.intervalsync.karoo.core.AthleteParams
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Rider's own CP and W′; null means "use the default". */
data class AthleteSettings(
    val cpW: Int? = null,
    val wPrimeJ: Int? = null,
) {
    /** CP falls back to the Karoo profile's FTP, W′ to [AthleteParams.DEFAULT_W_PRIME_J]. */
    fun resolve(karooFtpW: Int?): AthleteParams? {
        val cp = cpW ?: karooFtpW?.takeIf { it > 0 } ?: return null
        return AthleteParams(cp.toDouble(), wPrimeJ?.toDouble() ?: AthleteParams.DEFAULT_W_PRIME_J)
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("athlete", Context.MODE_PRIVATE)

    fun read(): AthleteSettings = AthleteSettings(
        cpW = prefs.getInt(KEY_CP_W, 0).takeIf { it > 0 },
        wPrimeJ = prefs.getInt(KEY_W_PRIME_J, 0).takeIf { it > 0 },
    )

    fun write(settings: AthleteSettings) {
        prefs.edit()
            .putInt(KEY_CP_W, settings.cpW ?: 0)
            .putInt(KEY_W_PRIME_J, settings.wPrimeJ ?: 0)
            .apply()
    }

    /** Current settings, then again on every change. */
    fun flow(): Flow<AthleteSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        private const val KEY_CP_W = "cp_w"
        private const val KEY_W_PRIME_J = "w_prime_j"
    }
}
