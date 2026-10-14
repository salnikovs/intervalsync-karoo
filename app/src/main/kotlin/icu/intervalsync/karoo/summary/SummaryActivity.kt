package icu.intervalsync.karoo.summary

import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import icu.intervalsync.karoo.R
import icu.intervalsync.karoo.SettingsStore
import icu.intervalsync.karoo.account.AccountController
import icu.intervalsync.karoo.account.AccountState
import icu.intervalsync.karoo.account.AccountStore
import icu.intervalsync.karoo.account.IntervalsyncApi
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Opened from the end-of-ride notification; shows the last finished ride and sends its report. */
class SummaryActivity : ComponentActivity() {
    private val karooSystem by lazy { KarooSystemService(this) }
    private val store by lazy { SummaryStore(this) }
    private val account by lazy { AccountController(IntervalsyncApi(karooSystem), AccountStore(this), SettingsStore(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var summary by remember { mutableStateOf(store.read()) }
            var sending by remember { mutableStateOf(false) }
            var failed by remember { mutableStateOf(false) }
            val accountState by account.state.collectAsState()
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(stringResource(R.string.summary_title), style = MaterialTheme.typography.headlineSmall)
                        val shown = summary
                        if (shown == null) {
                            Text(stringResource(R.string.summary_none), style = MaterialTheme.typography.bodyMedium)
                        } else {
                            SummaryContent(shown)
                            if (accountState is AccountState.Paired) {
                                HorizontalDivider()
                                FeelPicker(shown.feel, enabled = !shown.sent && !sending) { feel ->
                                    summary = shown.copy(feel = feel).also { store.write(it) }
                                }
                                Button(
                                    enabled = !shown.sent && !sending,
                                    onClick = {
                                        sending = true
                                        failed = false
                                        lifecycleScope.launch {
                                            val saved = account.sendRideReport(shown.toReport())
                                            if (saved) summary = shown.copy(sent = true).also { store.write(it) }
                                            failed = !saved
                                            sending = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        stringResource(
                                            when {
                                                shown.sent -> R.string.report_sent
                                                sending -> R.string.report_sending
                                                failed -> R.string.report_failed
                                                else -> R.string.report_send
                                            },
                                        ),
                                    )
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
    }

    override fun onStop() {
        karooSystem.disconnect()
        super.onStop()
    }
}

/** The rider's own rating of the ride, on Intervals.icu's five-step scale. */
@Composable
private fun FeelPicker(selected: Int?, enabled: Boolean, onSelect: (Int) -> Unit) {
    Text(stringResource(R.string.feel_title), style = MaterialTheme.typography.titleMedium)
    FEELS.forEachIndexed { index, label ->
        val feel = index + 1
        if (feel == selected) {
            Button(onClick = { onSelect(feel) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }
        } else {
            OutlinedButton(onClick = { onSelect(feel) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }
        }
    }
}

private val FEELS = listOf(R.string.feel_1, R.string.feel_2, R.string.feel_3, R.string.feel_4, R.string.feel_5)

@Composable
internal fun SummaryContent(summary: RideSummary) {
    Text(
        DateUtils.getRelativeTimeSpanString(summary.endedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() +
            " · " + stringResource(R.string.summary_duration, summary.recordedSeconds / 3600, summary.recordedSeconds % 3600 / 60),
        style = MaterialTheme.typography.bodyMedium,
    )
    HorizontalDivider()
    Metric(stringResource(R.string.summary_average_power), summary.averagePowerW?.let { stringResource(R.string.watts, it.roundToInt()) })
    Metric(stringResource(R.string.best_5min), summary.best5MinW?.let { stringResource(R.string.watts, it.roundToInt()) })
    Metric(stringResource(R.string.best_20min), summary.best20MinW?.let { stringResource(R.string.watts, it.roundToInt()) })
    Metric(
        stringResource(R.string.summary_w_prime_low),
        summary.wPrimeMinFraction?.let { stringResource(R.string.percent, (it * 100).roundToInt()) },
    )

    val zones = summary.zoneStress
    val total = zones?.sumOf { it.second } ?: 0.0
    if (zones != null && total > 0) {
        HorizontalDivider()
        Text(stringResource(R.string.summary_zones), style = MaterialTheme.typography.titleMedium)
        val largest = zones.maxOf { it.second }
        zones.forEachIndexed { index, (name, stress) ->
            ZoneBar(name, zoneColor(index, zones.size), share = stress / total, length = stress / largest)
        }
    }
}

@Composable
private fun Metric(label: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value ?: stringResource(R.string.no_value), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ZoneBar(name: String, color: Color, share: Double, length: Double) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(112.dp))
        Box(modifier = Modifier.weight(1f).height(14.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(length.toFloat().coerceIn(0f, 1f))
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color),
            )
        }
        Text(stringResource(R.string.percent, (share * 100).roundToInt()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(44.dp))
    }
}

/** Easy to hard along the logo's grey-green-coral range, spread over however many zones the rider has. */
private fun zoneColor(index: Int, count: Int): Color {
    val position = if (count > 1) index.toFloat() / (count - 1) * (ZoneRamp.size - 1) else 0f
    val lower = position.toInt().coerceAtMost(ZoneRamp.size - 2)
    return lerp(ZoneRamp[lower], ZoneRamp[lower + 1], position - lower)
}

private val ZoneRamp = listOf(
    Color(0xFF7A8C80),
    Color(0xFF86B82E),
    Color(0xFFB8F04A),
    Color(0xFFFFD166),
    Color(0xFFFF9F5C),
    Color(0xFFFF7F5C),
)
