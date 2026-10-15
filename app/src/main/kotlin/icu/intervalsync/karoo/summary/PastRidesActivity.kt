package icu.intervalsync.karoo.summary

import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import icu.intervalsync.karoo.R
import icu.intervalsync.karoo.SettingsStore
import icu.intervalsync.karoo.account.AccountController
import icu.intervalsync.karoo.account.AccountStore
import icu.intervalsync.karoo.account.CoachComment
import icu.intervalsync.karoo.account.IntervalsyncApi
import icu.intervalsync.karoo.account.PastRide
import icu.intervalsync.karoo.account.RideRecord
import icu.intervalsync.karoo.core.RideTracker
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Rides fetched back from Intervals.icu through Intervalsync, each summarized on the
 * Karoo by the same code that tracks a live ride.
 */
class PastRidesActivity : ComponentActivity() {
    private sealed interface Screen {
        data object Loading : Screen

        data object Failed : Screen

        data class Rides(val rides: List<PastRide>) : Screen

        data class Summary(val ride: PastRide, val summary: RideSummary, val comment: CoachComment?) : Screen
    }

    private val karooSystem by lazy { KarooSystemService(this) }
    private val settings by lazy { SettingsStore(this) }
    private val account by lazy { AccountController(IntervalsyncApi(karooSystem), AccountStore(this), settings) }
    private val screen = MutableStateFlow<Screen>(Screen.Loading)
    private var rides: List<PastRide> = emptyList()
    private var karooFtp: Int? = null
    private var profileConsumer: String? = null
    /** Opened as the last ride summary: go straight to the newest ride, once. */
    private var openLatest = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openLatest = savedInstanceState == null && intent.getBooleanExtra(EXTRA_OPEN_LATEST, false)

        setContent {
            val current by screen.collectAsState()
            BackHandler(enabled = current is Screen.Summary) { screen.value = Screen.Rides(rides) }
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when (val shown = current) {
                            is Screen.Loading -> {
                                Text(stringResource(R.string.past_rides_title), style = MaterialTheme.typography.headlineSmall)
                                Text(stringResource(R.string.past_rides_loading), style = MaterialTheme.typography.bodyMedium)
                            }
                            is Screen.Failed -> {
                                Text(stringResource(R.string.past_rides_title), style = MaterialTheme.typography.headlineSmall)
                                Text(stringResource(R.string.past_rides_failed), style = MaterialTheme.typography.bodyMedium)
                                OutlinedButton(onClick = { loadRides() }, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.account_retry))
                                }
                            }
                            is Screen.Rides -> {
                                Text(stringResource(R.string.past_rides_title), style = MaterialTheme.typography.headlineSmall)
                                if (shown.rides.isEmpty()) {
                                    Text(stringResource(R.string.past_rides_empty), style = MaterialTheme.typography.bodyMedium)
                                }
                                shown.rides.forEach { ride ->
                                    HorizontalDivider()
                                    Column(modifier = Modifier.fillMaxWidth().clickable { openRide(ride) }.padding(vertical = 6.dp)) {
                                        Text(ride.name, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            DateUtils.formatDateTime(this@PastRidesActivity, ride.startedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL) +
                                                " · " + stringResource(R.string.summary_duration, ride.durationSeconds / 3600, ride.durationSeconds % 3600 / 60),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        RecordBadges(ride.records)
                                    }
                                }
                            }
                            is Screen.Summary -> {
                                Text(shown.ride.name, style = MaterialTheme.typography.headlineSmall)
                                RecordBadges(shown.ride.records)
                                var tab by rememberSaveable(shown.ride.id) { mutableIntStateOf(0) }
                                TabRow(selectedTabIndex = tab) {
                                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.ride_tab_ride)) })
                                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.ride_tab_coach)) })
                                }
                                if (tab == 0) {
                                    SummaryContent(shown.summary)
                                    if (shown.ride.records.isNotEmpty()) {
                                        HorizontalDivider()
                                        Text(stringResource(R.string.records_title), style = MaterialTheme.typography.titleMedium)
                                        shown.ride.records.forEach { record ->
                                            Metric(recordDuration(record.durationSeconds), stringResource(R.string.watts, record.watts))
                                        }
                                    }
                                } else {
                                    RideComment(shown.comment)
                                }
                                OutlinedButton(onClick = { screen.value = Screen.Rides(rides) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.past_rides_back))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        karooSystem.connect()
        profileConsumer = karooSystem.addConsumer { profile: UserProfile ->
            karooFtp = profile.ftp.takeIf { it > 0 }
        }
        if (screen.value !is Screen.Summary) loadRides()
    }

    override fun onStop() {
        profileConsumer?.let { karooSystem.removeConsumer(it) }
        profileConsumer = null
        karooSystem.disconnect()
        super.onStop()
    }

    private fun loadRides() {
        screen.value = Screen.Loading
        lifecycleScope.launch {
            val fetched = account.pastRides()
            if (fetched != null) rides = fetched
            screen.value = if (fetched != null) Screen.Rides(fetched) else Screen.Failed
            if (openLatest) {
                openLatest = false
                fetched?.firstOrNull()?.let { openRide(it) }
            }
        }
    }

    private fun openRide(ride: PastRide) {
        screen.value = Screen.Loading
        lifecycleScope.launch {
            val power = account.ridePower(ride.id)
            if (power == null) {
                screen.value = Screen.Failed
                return@launch
            }
            val athlete = settings.read()
            val ftp = athlete.resolveFtp(karooFtp)
            val snapshot = withContext(Dispatchers.Default) {
                RideTracker.summarize(power, athlete.resolve(karooFtp), ftp?.toDouble(), athlete.powerZones.map { it.max?.toDouble() })
            }
            val summary = RideSummary.of(snapshot, ride.startedAt + ride.durationSeconds * 1000, athlete.powerZones.map { it.name })
            screen.value = Screen.Summary(ride, summary, account.coach()?.commentFor(ride))
        }
    }

    companion object {
        const val EXTRA_OPEN_LATEST = "open_latest"
    }
}

/** A badge for each best this ride set, shortest first. */
@Composable
private fun RecordBadges(records: List<RideRecord>) {
    if (records.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        records.forEach { record ->
            Text(
                "★ " + recordDuration(record.durationSeconds),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun recordDuration(seconds: Int): String =
    if (seconds < 60) stringResource(R.string.duration_seconds, seconds) else stringResource(R.string.duration_minutes, seconds / 60)

@Composable
private fun RideComment(comment: CoachComment?) {
    if (comment == null) {
        Text(stringResource(R.string.ride_comment_none), style = MaterialTheme.typography.bodyMedium)
        return
    }
    listOfNotNull(comment.title, comment.author).takeIf { it.isNotEmpty() }?.let {
        Text(it.joinToString(" · "), style = MaterialTheme.typography.titleMedium)
    }
    Text(comment.text, style = MaterialTheme.typography.bodyMedium)
}
