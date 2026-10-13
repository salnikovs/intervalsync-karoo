package icu.intervalsync.karoo.extension

import icu.intervalsync.karoo.SettingsStore
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import timber.log.Timber
import kotlin.math.roundToInt

class IntervalsyncExtension : KarooExtension("intervalsync", "0.1.0") {
    private val karooSystem by lazy { KarooSystemService(this) }
    private val engine by lazy { RideEngine(karooSystem, SettingsStore(this)) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val types by lazy {
        listOf(
            SnapshotDataType(extension, "wbal-kj", engine) { s -> s.wPrimeBalanceJ?.let { (it / 100).roundToInt() / 10.0 } },
            SnapshotDataType(extension, "wbal-pct", engine) { s -> s.wPrimeFraction?.let { (it * 100).roundToInt().toDouble() } },
            SnapshotDataType(extension, "best-5min", engine) { s -> s.best5MinW?.roundToInt()?.toDouble() },
            SnapshotDataType(extension, "best-20min", engine) { s -> s.best20MinW?.roundToInt()?.toDouble() },
        )
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem.connect { connected ->
            Timber.i("Karoo system connected=$connected")
        }
        engine.start(scope)
    }

    override fun onDestroy() {
        scope.cancel()
        karooSystem.disconnect()
        super.onDestroy()
    }
}
