package icu.intervalsync.karoo.extension

import android.os.SystemClock
import icu.intervalsync.karoo.SettingsStore
import icu.intervalsync.karoo.account.RideTargets
import icu.intervalsync.karoo.core.AthleteParams
import icu.intervalsync.karoo.core.CapMonitor
import icu.intervalsync.karoo.core.RideSnapshot
import icu.intervalsync.karoo.core.RideTracker
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Samples Karoo's power stream at 1 Hz and feeds it to a [RideTracker] for the current ride.
 *
 * A new tracker starts each time the ride goes from Idle to Recording.
 */
class RideEngine(
    private val karooSystem: KarooSystemService,
    private val settings: SettingsStore,
    /** Called once per ride, when it goes back to Idle after at least [MIN_RIDE_SECONDS] with power. */
    private val onRideEnded: (RideSnapshot) -> Unit = {},
    /** Today's caps from the assistant, read once as each ride starts. */
    private val targets: () -> RideTargets? = { null },
    /** Called when the 10-second average goes over a cap; at most once per cap every two minutes. */
    private val onOverCap: (Cap, Int) -> Unit = { _, _ -> },
) {
    enum class Cap { POWER, HEART_RATE }

    /** The caps in force for the current ride; null outside a ride or when none were set. */
    @Volatile
    var activeTargets: RideTargets? = null
        private set

    @Volatile
    private var heartRate: Double? = null

    private val _snapshots = MutableStateFlow<RideSnapshot?>(null)

    /** Latest metrics; null until a power meter has been seen. */
    val snapshots: StateFlow<RideSnapshot?> = _snapshots

    @Volatile
    private var powerW: Double? = null

    @Volatile
    private var rideState: RideState = RideState.Idle

    @Volatile
    private var params: AthleteParams? = null

    @Volatile
    private var ftpW: Double? = null

    /** The rider's power zone upper bounds from Intervalsync; null until synced. */
    @Volatile
    private var zoneMaxW: List<Double?>? = null

    fun start(scope: CoroutineScope) {
        scope.launch {
            karooSystem.streamDataFlow(DataType.Type.POWER).collect {
                powerW = (it as? StreamState.Streaming)?.dataPoint?.singleValue
            }
        }
        scope.launch {
            karooSystem.streamDataFlow(DataType.Type.HEART_RATE).collect {
                heartRate = (it as? StreamState.Streaming)?.dataPoint?.singleValue
            }
        }
        scope.launch {
            karooSystem.consumerFlow<RideState>().collect {
                Timber.d("Ride state now $it")
                rideState = it
            }
        }
        scope.launch {
            val karooFtp = karooSystem.consumerFlow<UserProfile>()
                .map<UserProfile, Int?> { it.ftp }
                .onStart { emit(null) }
            combine(settings.flow(), karooFtp) { athlete, ftp -> Pair(athlete, ftp) }.collect { (athlete, ftp) ->
                params = athlete.resolve(ftp)
                ftpW = athlete.resolveFtp(ftp)?.toDouble()
                zoneMaxW = athlete.powerZones.takeIf { it.isNotEmpty() }?.map { it.max?.toDouble() }
                Timber.d("Athlete params now $params, FTP $ftpW, ${athlete.powerZones.size} power zones")
            }
        }
        scope.launch { sample() }
    }

    private suspend fun CoroutineScope.sample() {
        var tracker = RideTracker(params)
        var trackerParams = params
        var idle = true
        var seenPower = false
        var powerCap = CapMonitor()
        var heartRateCap = CapMonitor()
        var last = SystemClock.elapsedRealtime()
        while (isActive) {
            delay(200)
            // Whole seconds since the last sample, so a slow loop catches up instead of losing time
            val seconds = (SystemClock.elapsedRealtime() - last) / 1000
            last += seconds * 1000
            repeat(seconds.coerceAtMost(MAX_CATCH_UP_SECONDS).toInt()) {
                val state = rideState
                if (state is RideState.Idle) {
                    if (!idle) {
                        // Recording or Paused -> Idle: the ride is over
                        val final = tracker.snapshot()
                        if (final.recordedSeconds >= MIN_RIDE_SECONDS) onRideEnded(final)
                        activeTargets = null
                    }
                    idle = true
                    return@repeat
                }
                if (idle) {
                    // Idle -> Recording: a new ride
                    idle = false
                    tracker = RideTracker(params, ftpW, zoneMaxW)
                    trackerParams = params
                    activeTargets = targets()
                    powerCap = CapMonitor()
                    heartRateCap = CapMonitor()
                }
                val caps = activeTargets
                if (caps != null && state is RideState.Recording) {
                    caps.powerMaxW?.let { if (powerCap.tick(powerW, it.toDouble())) onOverCap(Cap.POWER, it) }
                    caps.hrMaxBpm?.let { if (heartRateCap.tick(heartRate, it.toDouble())) onOverCap(Cap.HEART_RATE, it) }
                }
                if (trackerParams != params) {
                    trackerParams = params
                    tracker.updateParams(trackerParams)
                }
                val power = powerW
                if (power != null) seenPower = true
                // Once a power meter has been seen, a dropout counts as 0 W, the same as the recorded file
                if (seenPower) {
                    _snapshots.value = tracker.tick(power ?: 0.0, recording = state is RideState.Recording)
                }
            }
        }
    }

    companion object {
        private const val MAX_CATCH_UP_SECONDS = 600L
        private const val MIN_RIDE_SECONDS = 60L
    }
}
