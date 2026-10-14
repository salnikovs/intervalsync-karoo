package icu.intervalsync.karoo.summary

import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import icu.intervalsync.karoo.R
import icu.intervalsync.karoo.SettingsStore
import icu.intervalsync.karoo.account.AccountController
import icu.intervalsync.karoo.account.AccountStore
import icu.intervalsync.karoo.account.IntervalsyncApi
import icu.intervalsync.karoo.account.PastRide
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

        data class Summary(val ride: PastRide, val summary: RideSummary) : Screen
    }

    private val karooSystem by lazy { KarooSystemService(this) }
    private val settings by lazy { SettingsStore(this) }
    private val account by lazy { AccountController(IntervalsyncApi(karooSystem), AccountStore(this), settings) }
    private val screen = MutableStateFlow<Screen>(Screen.Loading)
    private var rides: List<PastRide> = emptyList()
    private var karooFtp: Int? = null
    private var profileConsumer: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                                    }
                                }
                            }
                            is Screen.Summary -> {
                                Text(shown.ride.name, style = MaterialTheme.typography.headlineSmall)
                                SummaryContent(shown.summary)
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
            screen.value = Screen.Summary(ride, RideSummary.of(snapshot, ride.startedAt + ride.durationSeconds * 1000, athlete.powerZones.map { it.name }))
        }
    }
}
