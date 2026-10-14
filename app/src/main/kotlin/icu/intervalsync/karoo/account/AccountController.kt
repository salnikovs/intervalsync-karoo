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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    /** Last trends response and when it was fetched, so opening the app doesn't refetch it. */
    var trendsJson: String?
        get() = prefs.getString(KEY_TRENDS, null)
        set(value) = prefs.edit().putString(KEY_TRENDS, value).apply()

    var contextJson: String?
        get() = prefs.getString(KEY_CONTEXT, null)
        set(value) = prefs.edit().putString(KEY_CONTEXT, value).apply()

    var contextAt: Long
        get() = prefs.getLong(KEY_CONTEXT_AT, 0)
        set(value) = prefs.edit().putLong(KEY_CONTEXT_AT, value).apply()

    var workoutsJson: String?
        get() = prefs.getString(KEY_WORKOUTS, null)
        set(value) = prefs.edit().putString(KEY_WORKOUTS, value).apply()

    var workoutsAt: Long
        get() = prefs.getLong(KEY_WORKOUTS_AT, 0)
        set(value) = prefs.edit().putLong(KEY_WORKOUTS_AT, value).apply()

    var spacesJson: String?
        get() = prefs.getString(KEY_SPACES, null)
        set(value) = prefs.edit().putString(KEY_SPACES, value).apply()

    var spacesAt: Long
        get() = prefs.getLong(KEY_SPACES_AT, 0)
        set(value) = prefs.edit().putLong(KEY_SPACES_AT, value).apply()

    var coachJson: String?
        get() = prefs.getString(KEY_COACH, null)
        set(value) = prefs.edit().putString(KEY_COACH, value).apply()

    var coachAt: Long
        get() = prefs.getLong(KEY_COACH_AT, 0)
        set(value) = prefs.edit().putLong(KEY_COACH_AT, value).apply()

    var trendsAt: Long
        get() = prefs.getLong(KEY_TRENDS_AT, 0)
        set(value) = prefs.edit().putLong(KEY_TRENDS_AT, value).apply()

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_ATHLETE_NAME = "athlete_name"
        private const val KEY_SYNCED_AT = "synced_at"
        private const val KEY_TRENDS = "trends"
        private const val KEY_CONTEXT = "context"
        private const val KEY_WORKOUTS = "workouts"
        private const val KEY_COACH = "coach"
        private const val KEY_SPACES = "spaces"
        private const val KEY_SPACES_AT = "spaces_at"
        private const val KEY_COACH_AT = "coach_at"
        private const val KEY_WORKOUTS_AT = "workouts_at"
        private const val KEY_CONTEXT_AT = "context_at"
        private const val KEY_TRENDS_AT = "trends_at"
    }
}

/** A message someone posted to a shared space; [date] is yyyy-MM-dd. */
data class SpaceUpdate(val author: String, val date: String, val text: String)

/** A coach or team space and the brief its members share. These are other people's words. */
data class SharedSpace(
    val name: String,
    /** True for a coach and their athlete, false for a team. */
    val coach: Boolean,
    val goal: String?,
    val focus: String?,
    val priorities: List<String>,
    val constraints: String?,
    val updates: List<SpaceUpdate>,
)

/** A note the assistant wrote after reviewing a ride or a week; [date] is yyyy-MM-dd. */
data class CoachComment(val date: String, val week: Boolean, val title: String?, val text: String)

/** What a day's workout is for, in the assistant's words; [date] is yyyy-MM-dd. */
data class RideBrief(val date: String, val text: String)

/** Caps the assistant set for one day's ride; [date] is yyyy-MM-dd. */
data class RideTargets(val date: String, val powerMaxW: Int?, val hrMaxBpm: Int?, val note: String?)

data class Coach(val comments: List<CoachComment>, val brief: RideBrief?, val targets: RideTargets?) {
    /** Briefs and targets are for one day only. */
    fun briefFor(date: String): RideBrief? = brief?.takeIf { it.date == date }

    fun targetsFor(date: String): RideTargets? = targets?.takeIf { it.date == date }

    companion object {
        /** Today in the Karoo's own timezone, as yyyy-MM-dd. */
        fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
}

/** A workout on the rider's calendar; [date] is yyyy-MM-dd. */
data class PlannedWorkout(
    val date: String,
    val name: String,
    val type: String?,
    val durationSeconds: Long?,
    val load: Int?,
    val description: String?,
)

/** Each section in the rider's own words; null where they haven't saved one. */
data class TrainingContext(
    val goal: String?,
    val events: String?,
    val constraints: String?,
    val preferences: String?,
    val notes: String?,
)

/** One week of training; [weekStart] is the Monday as yyyy-MM-dd. CP and W′ are null where they couldn't be modelled. */
data class TrendWeek(val weekStart: String, val hours: Double, val cpW: Double?, val wPrimeJ: Double?)

/** Weeks oldest first, ending with the current, partial week. */
data class Trends(val weeks: List<TrendWeek>)

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
            powerZones = json["power_zones"] as? JsonArray,
            hrZones = json["hr_zones"] as? JsonArray,
        )
        json.string("athlete_name")?.let { account.athleteName = it }
        account.syncedAt = System.currentTimeMillis()
        _state.value = current()
        return true
    }

    /**
     * The training context saved to the rider's Intervalsync account. Served from the last
     * fetch when that is recent enough; null if nothing is cached and it couldn't be fetched.
     */
    suspend fun trainingContext(): TrainingContext? {
        val cached = account.contextJson?.let { parseContext(it) }
        if (cached != null && System.currentTimeMillis() - account.contextAt < TRENDS_STALE_MS) return cached
        val json = get("/api/device/context") ?: return cached
        account.contextJson = json.toString()
        account.contextAt = System.currentTimeMillis()
        return parseContext(json.toString()) ?: cached
    }

    private fun parseContext(stored: String): TrainingContext? {
        val json = runCatching { Json.parseToJsonElement(stored) as? JsonObject }.getOrNull() ?: return null
        fun section(key: String): String? = json.string(key)?.trim()?.takeIf { it.isNotEmpty() }
        return TrainingContext(section("goal"), section("events"), section("constraints"), section("preferences"), section("notes"))
    }

    /**
     * The coach and team spaces the rider can read, with each one's shared brief and latest
     * updates. Served from the last fetch when that is recent enough; null if nothing is
     * cached and it couldn't be fetched.
     */
    suspend fun sharedSpaces(): List<SharedSpace>? {
        val cached = account.spacesJson?.let { parseSpaces(it) }
        if (cached != null && System.currentTimeMillis() - account.spacesAt < WORKOUTS_STALE_MS) return cached
        val json = get("/api/device/spaces") ?: return cached
        val spaces = parseSpaces(json.toString()) ?: return cached
        account.spacesJson = json.toString()
        account.spacesAt = System.currentTimeMillis()
        return spaces
    }

    private fun parseSpaces(stored: String): List<SharedSpace>? {
        val json = runCatching { Json.parseToJsonElement(stored) as? JsonObject }.getOrNull()
        val spaces = json?.get("spaces") as? JsonArray ?: return null
        fun JsonObject.text(key: String): String? = string(key)?.trim()?.takeIf { it.isNotEmpty() }
        return spaces.mapNotNull { element ->
            val space = element as? JsonObject ?: return@mapNotNull null
            val brief = space["brief"] as? JsonObject
            SharedSpace(
                name = space.text("name") ?: return@mapNotNull null,
                coach = space.string("kind") == "coach",
                goal = brief?.text("goal"),
                focus = brief?.text("focus"),
                priorities = (brief?.get("priorities") as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { text -> text.isNotEmpty() } },
                constraints = brief?.text("constraints"),
                updates = (space["updates"] as? JsonArray).orEmpty().mapNotNull { update ->
                    val posted = update as? JsonObject ?: return@mapNotNull null
                    SpaceUpdate(posted.text("author").orEmpty(), posted.string("date").orEmpty(), posted.text("text") ?: return@mapNotNull null)
                },
            )
        }
    }

    /**
     * The assistant's comments, today's brief and today's targets. Served from the last
     * fetch when that is recent enough; null if nothing is cached and it couldn't be fetched.
     */
    suspend fun coach(): Coach? {
        val cached = cachedCoach()
        if (cached != null && System.currentTimeMillis() - account.coachAt < WORKOUTS_STALE_MS) return cached
        val json = get("/api/device/coach") ?: return cached
        account.coachJson = json.toString()
        account.coachAt = System.currentTimeMillis()
        return cachedCoach()
    }

    /** The last fetched [coach] without touching the network, for use at ride start. */
    fun cachedCoach(): Coach? {
        val json = account.coachJson?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
        val comments = (json["comments"] as? JsonArray).orEmpty().mapNotNull { element ->
            val comment = element as? JsonObject ?: return@mapNotNull null
            CoachComment(
                date = comment.string("date") ?: return@mapNotNull null,
                week = comment.string("scope") == "week",
                title = comment.string("title")?.takeIf { it.isNotBlank() },
                text = comment.string("text")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
            )
        }
        val brief = (json["brief"] as? JsonObject)?.let { brief ->
            val text = brief.string("text")?.trim()?.takeIf { it.isNotEmpty() }
            val date = brief.string("date")
            if (text != null && date != null) RideBrief(date, text) else null
        }
        val targets = (json["targets"] as? JsonObject)?.let { targets ->
            RideTargets(
                date = targets.string("date") ?: return@let null,
                powerMaxW = targets.number("power_max_w")?.roundToInt()?.takeIf { it > 0 },
                hrMaxBpm = targets.number("hr_max_bpm")?.roundToInt()?.takeIf { it > 0 },
                note = targets.string("note")?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        return Coach(comments, brief, targets)
    }

    /** Send a finished ride's report for the assistant. Returns true once the service has saved it. */
    suspend fun sendRideReport(report: JsonObject): Boolean {
        val token = account.token ?: return false
        val response = try {
            api.request("POST", "/api/device/rides", report, token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Ride report failed")
            return false
        }
        if (response.status == 401) forget()
        return response.status in 200..299
    }

    /**
     * Workouts planned for today and the next 7 days, oldest first. Served from the last
     * fetch when that is recent enough; null if nothing is cached and it couldn't be fetched.
     */
    suspend fun plannedWorkouts(): List<PlannedWorkout>? {
        val cached = account.workoutsJson?.let { parseWorkouts(it) }
        if (cached != null && System.currentTimeMillis() - account.workoutsAt < WORKOUTS_STALE_MS) return cached
        val json = get("/api/device/workouts") ?: return cached
        val workouts = parseWorkouts(json.toString()) ?: return cached
        account.workoutsJson = json.toString()
        account.workoutsAt = System.currentTimeMillis()
        return workouts
    }

    private fun parseWorkouts(stored: String): List<PlannedWorkout>? {
        val json = runCatching { Json.parseToJsonElement(stored) as? JsonObject }.getOrNull()
        val workouts = json?.get("workouts") as? JsonArray ?: return null
        return workouts.mapNotNull { element ->
            val workout = element as? JsonObject ?: return@mapNotNull null
            PlannedWorkout(
                date = workout.string("date") ?: return@mapNotNull null,
                name = workout.string("name").orEmpty(),
                type = workout.string("type"),
                durationSeconds = workout.number("duration_s")?.toLong(),
                load = workout.number("load")?.roundToInt(),
                description = workout.string("description")?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }

    /**
     * Twelve weeks of training hours, CP and W′. Served from the last fetch when that is
     * recent enough; null if nothing is cached and it couldn't be fetched.
     */
    suspend fun trends(): Trends? {
        val cached = account.trendsJson?.let { parseTrends(it) }
        if (cached != null && System.currentTimeMillis() - account.trendsAt < TRENDS_STALE_MS) return cached
        val json = get("/api/device/trends") ?: return cached
        val trends = parseTrends(json.toString()) ?: return cached
        account.trendsJson = json.toString()
        account.trendsAt = System.currentTimeMillis()
        return trends
    }

    private fun parseTrends(stored: String): Trends? {
        val json = runCatching { Json.parseToJsonElement(stored) as? JsonObject }.getOrNull()
        val weeks = json?.get("weeks") as? JsonArray ?: return null
        return Trends(
            weeks.mapNotNull { element ->
                val week = element as? JsonObject ?: return@mapNotNull null
                TrendWeek(
                    weekStart = week.string("week_start") ?: return@mapNotNull null,
                    hours = week.number("hours") ?: 0.0,
                    cpW = week.number("cp_w"),
                    wPrimeJ = week.number("w_prime_j"),
                )
            },
        )
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
        settings.writeSynced(null, null, null, null, null)
        _state.value = AccountState.NotPaired
    }

    companion object {
        private const val STALE_MS = 24 * 60 * 60 * 1000L
        private const val TRENDS_STALE_MS = 6 * 60 * 60 * 1000L
        private const val WORKOUTS_STALE_MS = 60 * 60 * 1000L
    }
}
