package icu.intervalsync.karoo.extension

import android.os.SystemClock
import icu.intervalsync.karoo.SettingsStore
import icu.intervalsync.karoo.core.AthleteParams
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
) {
    private val _snapshots = MutableStateFlow<RideSnapshot?>(null)

    /** Latest metrics; null until a power meter has been seen. */
    val snapshots: StateFlow<RideSnapshot?> = _snapshots

    @Volatile
    private var powerW: Double? = null

    @Volatile
    private var rideState: RideState = RideState.Idle

    @Volatile
    private var params: AthleteParams? = null

    fun start(scope: CoroutineScope) {
        scope.launch {
            karooSystem.streamDataFlow(DataType.Type.POWER).collect {
                powerW = (it as? StreamState.Streaming)?.dataPoint?.singleValue
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
            combine(settings.flow(), karooFtp) { athlete, ftp -> athlete.resolve(ftp) }.collect {
                Timber.d("Athlete params now $it")
                params = it
            }
        }
        scope.launch { sample() }
    }

    private suspend fun CoroutineScope.sample() {
        var tracker = RideTracker(params)
        var trackerParams = params
        var idle = true
        var seenPower = false
        var last = SystemClock.elapsedRealtime()
        while (isActive) {
            delay(200)
            // Whole seconds since the last sample, so a slow loop catches up instead of losing time
            val seconds = (SystemClock.elapsedRealtime() - last) / 1000
            last += seconds * 1000
            repeat(seconds.coerceAtMost(MAX_CATCH_UP_SECONDS).toInt()) {
                val state = rideState
                if (state is RideState.Idle) {
                    idle = true
                    return@repeat
                }
                if (idle) {
                    // Idle -> Recording: a new ride
                    idle = false
                    tracker = RideTracker(params)
                    trackerParams = params
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
    }
}
