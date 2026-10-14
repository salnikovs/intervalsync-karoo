package icu.intervalsync.karoo.summary

import android.content.Context
import icu.intervalsync.karoo.account.number
import icu.intervalsync.karoo.core.PowerZone
import icu.intervalsync.karoo.core.RideSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    val zoneStress: Map<PowerZone, Double>?,
) {
    /** Lowest W′ balance as a share of W′, 0..1. */
    val wPrimeMinFraction: Double?
        get() = if (wPrimeMinJ != null && wPrimeCapacityJ != null) (wPrimeMinJ / wPrimeCapacityJ).coerceIn(0.0, 1.0) else null

    fun toJson(): JsonObject = buildJsonObject {
        put("ended_at", endedAt)
        put("recorded_s", recordedSeconds)
        put("average_power_w", averagePowerW)
        put("best_5min_w", best5MinW)
        put("best_20min_w", best20MinW)
        put("w_prime_min_j", wPrimeMinJ)
        put("w_prime_j", wPrimeCapacityJ)
        zoneStress?.forEach { (zone, stress) -> put(zoneKey(zone), stress) }
    }

    companion object {
        fun of(snapshot: RideSnapshot, endedAt: Long) = RideSummary(
            endedAt = endedAt,
            recordedSeconds = snapshot.recordedSeconds,
            averagePowerW = snapshot.averagePowerW,
            best5MinW = snapshot.best5MinW,
            best20MinW = snapshot.best20MinW,
            wPrimeMinJ = snapshot.wPrimeMinJ,
            wPrimeCapacityJ = snapshot.wPrimeCapacityJ,
            zoneStress = snapshot.zoneStress,
        )

        fun fromJson(json: JsonObject): RideSummary? {
            val zones = PowerZone.entries.mapNotNull { zone -> json.number(zoneKey(zone))?.let { zone to it } }.toMap()
            return RideSummary(
                endedAt = json.number("ended_at")?.toLong() ?: return null,
                recordedSeconds = json.number("recorded_s")?.toLong() ?: return null,
                averagePowerW = json.number("average_power_w"),
                best5MinW = json.number("best_5min_w"),
                best20MinW = json.number("best_20min_w"),
                wPrimeMinJ = json.number("w_prime_min_j"),
                wPrimeCapacityJ = json.number("w_prime_j"),
                zoneStress = zones.takeIf { it.size == PowerZone.entries.size },
            )
        }

        private fun zoneKey(zone: PowerZone) = "stress_" + zone.name.lowercase()
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
