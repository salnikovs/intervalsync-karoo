package icu.intervalsync.karoo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import icu.intervalsync.karoo.account.AccountController
import icu.intervalsync.karoo.account.AccountState
import icu.intervalsync.karoo.account.AccountStore
import icu.intervalsync.karoo.account.TrainingContextSection
import icu.intervalsync.karoo.account.TrendsSection
import icu.intervalsync.karoo.account.BriefCard
import icu.intervalsync.karoo.account.CoachCommentsSection
import icu.intervalsync.karoo.account.BillingSection
import icu.intervalsync.karoo.account.MembershipsSection
import icu.intervalsync.karoo.account.WellnessRow
import icu.intervalsync.karoo.account.IntervalsyncApi
import icu.intervalsync.karoo.account.TodaySection
import icu.intervalsync.karoo.account.PlanSection
import icu.intervalsync.karoo.summary.PastRidesActivity
import icu.intervalsync.karoo.summary.RideReporter
import icu.intervalsync.karoo.summary.SummaryActivity
import icu.intervalsync.karoo.summary.SummaryStore
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val TABS = listOf(R.string.tab_training, R.string.tab_plan, R.string.settings_section)

class MainActivity : ComponentActivity() {
    private val karooSystem by lazy { KarooSystemService(this) }
    private val settings by lazy { SettingsStore(this) }
    private val account by lazy { AccountController(IntervalsyncApi(karooSystem), AccountStore(this), settings) }
    private val karooFtp = MutableStateFlow<Int?>(null)
    private var profileConsumer: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var tab by rememberSaveable { mutableIntStateOf(0) }
                    Column(modifier = Modifier.fillMaxSize()) {
                        TabRow(selectedTabIndex = tab) {
                            TABS.forEachIndexed { index, title ->
                                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(title)) })
                            }
                        }
                        // Each tab keeps its own scroll position
                        val scroll = when (tab) {
                            0 -> rememberScrollState()
                            1 -> rememberScrollState()
                            else -> rememberScrollState()
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scroll)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (tab == 0) {
                                val accountState by account.state.collectAsState()
                                if (accountState !is AccountState.Paired) {
                                    Text(stringResource(R.string.training_not_connected), style = MaterialTheme.typography.bodyMedium)
                                }
                                BriefCard(account)
                                WellnessRow(account)
                                TrendsSection(account, settings)
                                CoachCommentsSection(account)
                                HorizontalDivider()
                                if (accountState is AccountState.Paired) {
                                    Button(
                                        onClick = { startActivity(Intent(this@MainActivity, PastRidesActivity::class.java)) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(stringResource(R.string.past_rides_open))
                                    }
                                }
                                OutlinedButton(
                                    onClick = { openLastRide(accountState is AccountState.Paired) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.summary_open))
                                }
                            } else if (tab == 1) {
                                TodaySection(account)
                                PlanSection(account)
                            } else {
                                AccountSection(account)
                                HorizontalDivider()
                                TrainingContextSection(account)
                                MembershipsSection(account)
                                SettingsSection(settings, karooFtp)
                                BillingSection(account)
                            }
                        }
                    }
                }
            }
        }
    }

    /** The last ride recorded here; without one, the newest ride fetched back from Intervals.icu. */
    private fun openLastRide(paired: Boolean) {
        val intent = if (SummaryStore(this).read() == null && paired) {
            Intent(this, PastRidesActivity::class.java).putExtra(PastRidesActivity.EXTRA_OPEN_LATEST, true)
        } else {
            Intent(this, SummaryActivity::class.java)
        }
        startActivity(intent)
    }

    override fun onStart() {
        super.onStart()
        karooSystem.connect()
        profileConsumer = karooSystem.addConsumer { profile: UserProfile ->
            karooFtp.value = profile.ftp.takeIf { it > 0 }
        }
        lifecycleScope.launch { account.syncIfStale() }
        // A ride report that couldn't go when the ride ended
        lifecycleScope.launch { RideReporter.sendPending(account, SummaryStore(this@MainActivity)) }
    }

    override fun onStop() {
        account.cancelPairing()
        profileConsumer?.let { karooSystem.removeConsumer(it) }
        profileConsumer = null
        karooSystem.disconnect()
        super.onStop()
    }
}

@Composable
fun AccountSection(account: AccountController) {
    val state by account.state.collectAsState()
    val scope = rememberCoroutineScope()

    when (val current = state) {
        is AccountState.NotPaired, is AccountState.Failed -> {
            Text(
                if (current is AccountState.Failed) {
                    when (current.reason) {
                        AccountState.Failed.Reason.NOT_AVAILABLE -> stringResource(R.string.account_not_available)
                        AccountState.Failed.Reason.START_REFUSED -> stringResource(R.string.account_failed_start, current.status ?: 0)
                        AccountState.Failed.Reason.EXPIRED -> stringResource(R.string.account_failed_expired)
                        AccountState.Failed.Reason.NO_CONNECTION -> stringResource(R.string.account_failed_connection)
                    }
                } else {
                    stringResource(R.string.account_intro)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { account.startPairing(scope) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (current is AccountState.Failed) R.string.account_retry else R.string.account_connect))
            }
        }
        is AccountState.Pairing -> {
            Text(stringResource(R.string.account_pairing_open, current.url), style = MaterialTheme.typography.bodyMedium)
            Text(current.code.chunked(3).joinToString(" "), style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.account_pairing_waiting), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { account.cancelPairing() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.account_cancel))
            }
        }
        is AccountState.Paired -> {
            Text(
                current.athleteName?.let { stringResource(R.string.account_connected_as, it) } ?: stringResource(R.string.account_connected),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scope.launch { account.syncAthlete() } },
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, SyncColor),
                    contentPadding = ButtonPadding,
                ) {
                    Text(stringResource(R.string.account_sync), color = SyncColor, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                OutlinedButton(
                    onClick = { scope.launch { account.unpair() } },
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, DisconnectColor),
                    contentPadding = ButtonPadding,
                ) {
                    Text(stringResource(R.string.account_disconnect), color = DisconnectColor, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Read-only: what the rider set up in Intervals.icu, or what the app falls back to without a connection. */
@Composable
fun SettingsSection(settings: SettingsStore, karooFtp: StateFlow<Int?>) {
    val stored by remember { settings.flow() }.collectAsState(settings.read())
    val profileFtp by karooFtp.collectAsState()
    val params = stored.resolve(profileFtp)
    val ftp = stored.resolveFtp(profileFtp)

    Text(
        stringResource(if (stored.syncedCpW != null || stored.syncedFtpW != null) R.string.settings_from_intervals else R.string.settings_from_karoo),
        style = MaterialTheme.typography.bodyMedium,
    )
    ValueRow(stringResource(R.string.settings_cp), params?.let { stringResource(R.string.watts, it.cpW.roundToInt()) })
    ValueRow(stringResource(R.string.settings_w_prime), params?.let { stringResource(R.string.kilojoules, formatKj(it.wPrimeJ.roundToInt())) })
    ValueRow(stringResource(R.string.settings_ftp), ftp?.let { stringResource(R.string.watts, it) })
}

@Composable
private fun ValueRow(label: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value ?: stringResource(R.string.no_value), style = MaterialTheme.typography.titleMedium)
    }
}

/** Each zone as "Z2 Endurance   137–186 W"; the range starts where the zone below ended. */
private fun formatKj(joules: Int): String = (joules / 1000.0).toString().removeSuffix(".0")

/** The app's light green, as in the trend charts. */
private val SyncColor = Color(0xFFB8F04A)
private val DisconnectColor = Color(0xFFFF6B5C)

/** Narrower than the default so both labels fit side by side on the Karoo's screen. */
private val ButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
