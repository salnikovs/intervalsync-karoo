package icu.intervalsync.karoo.extension

import icu.intervalsync.karoo.BuildConfig
import icu.intervalsync.karoo.R
import icu.intervalsync.karoo.SettingsStore
import icu.intervalsync.karoo.account.AccountController
import icu.intervalsync.karoo.account.AccountState
import icu.intervalsync.karoo.account.AccountStore
import icu.intervalsync.karoo.account.Coach
import icu.intervalsync.karoo.account.RideTargets
import icu.intervalsync.karoo.account.IntervalsyncApi
import icu.intervalsync.karoo.core.RideSnapshot
import icu.intervalsync.karoo.summary.RideReporter
import icu.intervalsync.karoo.summary.RideSummary
import icu.intervalsync.karoo.summary.SummaryStore
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.SystemNotification
import io.hammerhead.karooext.extension.KarooExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.roundToInt

class IntervalsyncExtension : KarooExtension("intervalsync", BuildConfig.VERSION_NAME) {
    private val karooSystem by lazy { KarooSystemService(this) }
    private val settings by lazy { SettingsStore(this) }
    private val engine by lazy {
        RideEngine(karooSystem, settings, ::onRideEnded, { todaysTargets() }, ::onOverCap)
    }
    private val account by lazy { AccountController(IntervalsyncApi(karooSystem), AccountStore(this), settings) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val types by lazy {
        listOf(
            WPrimeGraphicDataType(extension, engine),
            SnapshotDataType(extension, "wbal-kj", engine) { s -> s.wPrimeBalanceJ?.let { (it / 100).roundToInt() / 10.0 } },
            SnapshotDataType(extension, "wbal-pct", engine) { s -> s.wPrimeFraction?.let { (it * 100).roundToInt().toDouble() } },
            SnapshotDataType(extension, "best-5min", engine) { s -> s.best5MinW?.roundToInt()?.toDouble() },
            SnapshotDataType(extension, "best-20min", engine) { s -> s.best20MinW?.roundToInt()?.toDouble() },
            TargetDataType(extension, "power-cap") { todaysTargets()?.powerMaxW },
            TargetDataType(extension, "hr-cap") { todaysTargets()?.hrMaxBpm },
        )
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem.connect { connected ->
            Timber.i("Karoo system connected=$connected")
        }
        engine.start(scope)
        scope.launch { sendRideReport() }
        scope.launch {
            account.syncIfStale()
            // So today's targets are on the Karoo before the ride starts
            account.coach()
        }
    }

    private fun todaysTargets(): RideTargets? = account.cachedCoach()?.targetsFor(Coach.today())

    private fun onOverCap(cap: RideEngine.Cap, limit: Int) {
        karooSystem.dispatch(
            InRideAlert(
                id = "over-cap-${cap.name.lowercase()}",
                icon = R.drawable.ic_intervalsync,
                title = getString(if (cap == RideEngine.Cap.POWER) R.string.alert_over_power else R.string.alert_over_hr),
                detail = getString(if (cap == RideEngine.Cap.POWER) R.string.alert_cap_watts else R.string.alert_cap_bpm, limit),
                autoDismissMs = 8_000,
                backgroundColor = R.color.alert_background,
                textColor = R.color.alert_text,
            ),
        )
    }

    private fun onRideEnded(snapshot: RideSnapshot) {
        SummaryStore(this).write(RideSummary.of(snapshot, System.currentTimeMillis(), settings.read().powerZones.map { it.name }))
        scope.launch { sendRideReport() }
        karooSystem.dispatch(
            SystemNotification(
                id = "ride-summary",
                message = getString(if (account.state.value is AccountState.Paired) R.string.notification_feel else R.string.notification_summary),
                subText = snapshot.best5MinW?.let { getString(R.string.notification_summary_bests, it.roundToInt()) },
                action = getString(R.string.notification_view),
                actionIntent = "icu.intervalsync.karoo.SUMMARY",
            ),
        )
    }

    /** Sends the last ride's report, retrying while the Karoo is offline after the ride. */
    private suspend fun sendRideReport() {
        val store = SummaryStore(this)
        repeat(REPORT_ATTEMPTS) { attempt ->
            if (RideReporter.sendPending(account, store)) return
            if (account.state.value !is AccountState.Paired) return
            if (attempt < REPORT_ATTEMPTS - 1) delay(REPORT_RETRY_MS)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        karooSystem.disconnect()
        super.onDestroy()
    }

    private companion object {
        const val REPORT_ATTEMPTS = 12
        const val REPORT_RETRY_MS = 5 * 60_000L
    }
}
