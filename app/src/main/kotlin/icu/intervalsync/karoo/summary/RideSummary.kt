package icu.intervalsync.karoo.summary

import android.content.Context
import icu.intervalsync.karoo.account.number
import icu.intervalsync.karoo.account.string
import icu.intervalsync.karoo.core.RideSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** What the summary screen shows for a finished ride. */
data class RideSummary(
    /** Wall-clock millis when the ride ended. */
    val endedAt: Long,
    val recordedSeconds: Long,
    val averagePowerW: Double?,
    val best5MinW: Double?,
    val best20MinW: Double?,
    val wPrimeMinJ: Double?,
    val wPrimeCapacityJ: Double?,
    /** The rider's power zones, lowest first, each with the stress that fell in it. */
    val zoneStress: List<Pair<String, Double>>?,
    /** The rider's own rating, 1 (strong) to 5 (weak); null until they pick one. */
    val feel: Int? = null,
    /** True once this ride's report has been saved by Intervalsync. */
    val sent: Boolean = false,
) {
    /** Lowest W′ balance as a share of W′, 0..1. */
    val wPrimeMinFraction: Double?
        get() = if (wPrimeMinJ != null && wPrimeCapacityJ != null) (wPrimeMinJ / wPrimeCapacityJ).coerceIn(0.0, 1.0) else null

    /** The ride report sent for the assistant (see docs/device-api.md). */
    fun toReport(): JsonObject = buildJsonObject {
        put("ended_at_ms", endedAt)
        put("duration_s", recordedSeconds)
        put("average_power_w", averagePowerW)
        put("best_5min_w", best5MinW)
        put("best_20min_w", best20MinW)
        put("w_prime_min_j", wPrimeMinJ)
        put("w_prime_j", wPrimeCapacityJ)
        zoneStress?.let { zones ->
            putJsonArray("zones") {
                zones.forEach { (name, stress) ->
                    addJsonObject {
                        put("name", name)
                        put("stress", stress)
                    }
                }
            }
        }
        put("feel", feel)
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("feel", feel)
        put("sent", sent)
        put("ended_at", endedAt)
        put("recorded_s", recordedSeconds)
        put("average_power_w", averagePowerW)
        put("best_5min_w", best5MinW)
        put("best_20min_w", best20MinW)
        put("w_prime_min_j", wPrimeMinJ)
        put("w_prime_j", wPrimeCapacityJ)
        zoneStress?.let { zones ->
            putJsonArray("zones") {
                zones.forEach { (name, stress) ->
                    addJsonObject {
                        put("name", name)
                        put("stress", stress)
                    }
                }
            }
        }
    }

    companion object {
        /** @param zoneNames the rider's zone names, in the order the tracker was given the zones */
        fun of(snapshot: RideSnapshot, endedAt: Long, zoneNames: List<String>) = RideSummary(
            endedAt = endedAt,
            recordedSeconds = snapshot.recordedSeconds,
            averagePowerW = snapshot.averagePowerW,
            best5MinW = snapshot.best5MinW,
            best20MinW = snapshot.best20MinW,
            wPrimeMinJ = snapshot.wPrimeMinJ,
            wPrimeCapacityJ = snapshot.wPrimeCapacityJ,
            zoneStress = snapshot.zoneStress?.takeIf { it.size == zoneNames.size }?.let { zoneNames.zip(it) },
        )

        fun fromJson(json: JsonObject): RideSummary? {
            val zones = (json["zones"] as? JsonArray)?.mapNotNull { element ->
                val zone = element as? JsonObject ?: return@mapNotNull null
                zone.string("name").orEmpty() to (zone.number("stress") ?: 0.0)
            }
            return RideSummary(
                endedAt = json.number("ended_at")?.toLong() ?: return null,
                recordedSeconds = json.number("recorded_s")?.toLong() ?: return null,
                averagePowerW = json.number("average_power_w"),
                best5MinW = json.number("best_5min_w"),
                best20MinW = json.number("best_20min_w"),
                wPrimeMinJ = json.number("w_prime_min_j"),
                wPrimeCapacityJ = json.number("w_prime_j"),
                zoneStress = zones?.takeIf { it.isNotEmpty() },
                feel = json.number("feel")?.toInt(),
                sent = (json["sent"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
    }
}

/** The last finished ride's summary, kept so the screen can open after the extension is gone. */
class SummaryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("summary", Context.MODE_PRIVATE)

    fun write(summary: RideSummary) {
        prefs.edit().putString(KEY_LAST, summary.toJson().toString()).apply()
    }

    fun read(): RideSummary? {
        val stored = prefs.getString(KEY_LAST, null) ?: return null
        return runCatching { RideSummary.fromJson(Json.parseToJsonElement(stored) as JsonObject) }.getOrNull()
    }

    companion object {
        private const val KEY_LAST = "last"
    }
}
