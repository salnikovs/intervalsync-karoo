package icu.intervalsync.karoo.account

import android.content.Context
import android.os.SystemClock
import icu.intervalsync.karoo.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import timber.log.Timber
import kotlin.math.roundToInt

/** The device token from pairing; the Karoo never holds an Intervals.icu credential. */
class AccountStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("account", Context.MODE_PRIVATE)

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var athleteName: String?
        get() = prefs.getString(KEY_ATHLETE_NAME, null)
        set(value) = prefs.edit().putString(KEY_ATHLETE_NAME, value).apply()

    /** Wall-clock millis of the last athlete sync, 0 if never. */
    var syncedAt: Long
        get() = prefs.getLong(KEY_SYNCED_AT, 0)
        set(value) = prefs.edit().putLong(KEY_SYNCED_AT, value).apply()

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_ATHLETE_NAME = "athlete_name"
        private const val KEY_SYNCED_AT = "synced_at"
    }
}

data class PastRide(val id: String, val name: String, val startedAt: Long, val durationSeconds: Long)

sealed interface AccountState {
    data object NotPaired : AccountState

    /** Waiting for the rider to enter [code] at [url]. */
    data class Pairing(val code: String, val url: String) : AccountState

    data class Paired(val athleteName: String?) : AccountState

    /** [status] is the HTTP status when the service refused to start a pairing. */
    data class Failed(val reason: Reason, val status: Int? = null) : AccountState {
        enum class Reason { NOT_AVAILABLE, START_REFUSED, EXPIRED, NO_CONNECTION }
    }
}

/**
 * Pairs this Karoo with an Intervalsync account and keeps the rider's CP and W′ in sync.
 */
class AccountController(
    private val api: IntervalsyncApi,
    private val account: AccountStore,
    private val settings: SettingsStore,
) {
    private val _state = MutableStateFlow(current())
    val state: StateFlow<AccountState> = _state

    private var pairingJob: Job? = null

    private fun current(): AccountState =
        if (account.token != null) AccountState.Paired(account.athleteName) else AccountState.NotPaired

    fun startPairing(scope: CoroutineScope) {
        pairingJob?.cancel()
        pairingJob = scope.launch {
            try {
                val created = api.request("POST", "/api/device/pairings", buildJsonObject { put("device", "Karoo") })
                val json = created.json
                val pairingId = json?.string("pairing_id")
                val code = json?.string("code")
                if (created.status != 201 || json == null || pairingId == null || code == null) {
                    // 404: this server has no device API
                    val reason = if (created.status == 404) AccountState.Failed.Reason.NOT_AVAILABLE else AccountState.Failed.Reason.START_REFUSED
                    _state.value = AccountState.Failed(reason, created.status)
                    return@launch
                }
                val url = json.string("verification_url") ?: "intervalsync.icu"
                val intervalMs = ((json.number("interval") ?: 5.0) * 1000).toLong().coerceAtLeast(1000)
                val deadline = SystemClock.elapsedRealtime() + ((json.number("expires_in") ?: 600.0) * 1000).toLong()
                _state.value = AccountState.Pairing(code, url)

                while (SystemClock.elapsedRealtime() < deadline) {
                    delay(intervalMs)
                    val poll = api.request("POST", "/api/device/pairings/token", buildJsonObject { put("pairing_id", pairingId) })
                    val result = poll.json
                    if (poll.status != 200 || result == null) break
                    if (result.string("status") == "approved") {
                        val token = result.string("token") ?: break
                        account.token = token
                        account.athleteName = result.string("athlete_name")
                        _state.value = current()
                        syncAthlete()
                        return@launch
                    }
                }
                _state.value = AccountState.Failed(AccountState.Failed.Reason.EXPIRED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Pairing failed")
                _state.value = AccountState.Failed(AccountState.Failed.Reason.NO_CONNECTION)
            }
        }
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        _state.value = current()
    }

    /** Pull CP, W′ and name from Intervalsync. Returns false if nothing was updated. */
    suspend fun syncAthlete(): Boolean {
        val token = account.token ?: return false
        val response = try {
            api.request("GET", "/api/device/athlete", token = token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Athlete sync failed")
            return false
        }
        if (response.status == 401) {
            // Revoked on the website
            forget()
            return false
        }
        val json = response.json
        if (response.status != 200 || json == null) return false
        settings.writeSynced(
            cpW = json.number("cp_w")?.roundToInt(),
            wPrimeJ = json.number("w_prime_j")?.roundToInt(),
            ftpW = json.number("ftp_w")?.roundToInt(),
        )
        json.string("athlete_name")?.let { account.athleteName = it }
        account.syncedAt = System.currentTimeMillis()
        _state.value = current()
        return true
    }

    /** Recent rides with power, newest first; null if they couldn't be fetched. */
    suspend fun pastRides(): List<PastRide>? {
        val json = get("/api/device/rides") ?: return null
        val rides = json["rides"] as? JsonArray ?: return null
        return rides.mapNotNull { element ->
            val ride = element as? JsonObject ?: return@mapNotNull null
            PastRide(
                id = ride.string("id") ?: return@mapNotNull null,
                name = ride.string("name").orEmpty(),
                startedAt = ride.number("started_at_ms")?.toLong() ?: return@mapNotNull null,
                durationSeconds = ride.number("duration_s")?.toLong() ?: 0,
            )
        }
    }

    /** One ride's 1 Hz power; null if it couldn't be fetched. */
    suspend fun ridePower(id: String): List<Double>? {
        val json = get("/api/device/rides/$id/power") ?: return null
        val watts = json["watts"] as? JsonArray ?: return null
        return watts.map { (it as? JsonPrimitive)?.doubleOrNull ?: 0.0 }
    }

    private suspend fun get(path: String): JsonObject? {
        val token = account.token ?: return null
        val response = try {
            api.request("GET", path, token = token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "GET $path failed")
            return null
        }
        if (response.status == 401) forget()
        return response.json.takeIf { response.status == 200 }
    }

    /** At most one sync a day unless asked for. */
    suspend fun syncIfStale() {
        if (System.currentTimeMillis() - account.syncedAt > STALE_MS) syncAthlete()
    }

    suspend fun unpair() {
        val token = account.token
        forget()
        if (token != null) {
            try {
                api.request("DELETE", "/api/device", token = token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Couldn't revoke the device token")
            }
        }
    }

    private fun forget() {
        account.clear()
        settings.writeSynced(null, null, null)
        _state.value = AccountState.NotPaired
    }

    companion object {
        private const val STALE_MS = 24 * 60 * 60 * 1000L
    }
}
