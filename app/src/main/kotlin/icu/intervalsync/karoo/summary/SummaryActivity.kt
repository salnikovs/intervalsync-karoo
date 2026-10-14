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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import icu.intervalsync.karoo.R
import icu.intervalsync.karoo.core.PowerZone
import kotlin.math.roundToInt

/** Opened from the end-of-ride notification; shows the last finished ride. */
class SummaryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val summary = SummaryStore(this).read()

        setContent {
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
                        if (summary == null) {
                            Text(stringResource(R.string.summary_none), style = MaterialTheme.typography.bodyMedium)
                        } else {
                            SummaryContent(summary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryContent(summary: RideSummary) {
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
    val total = zones?.values?.sum() ?: 0.0
    if (zones != null && total > 0) {
        HorizontalDivider()
        Text(stringResource(R.string.summary_zones), style = MaterialTheme.typography.titleMedium)
        val largest = zones.values.max()
        PowerZone.entries.forEach { zone ->
            val stress = zones[zone] ?: 0.0
            ZoneBar(zone, share = stress / total, length = stress / largest)
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
private fun ZoneBar(zone: PowerZone, share: Double, length: Double) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(zoneName(zone)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
        Box(modifier = Modifier.weight(1f).height(14.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(length.toFloat().coerceIn(0f, 1f))
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(ZoneColors[zone.ordinal]),
            )
        }
        Text(stringResource(R.string.percent, (share * 100).roundToInt()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(44.dp))
    }
}

private fun zoneName(zone: PowerZone): Int = when (zone) {
    PowerZone.RECOVERY -> R.string.zone_recovery
    PowerZone.ENDURANCE -> R.string.zone_endurance
    PowerZone.TEMPO -> R.string.zone_tempo
    PowerZone.THRESHOLD -> R.string.zone_threshold
    PowerZone.VO2MAX -> R.string.zone_vo2max
    PowerZone.ANAEROBIC -> R.string.zone_anaerobic
}

// Easy to hard, on the logo's green-to-coral range
private val ZoneColors = listOf(
    Color(0xFF7A8C80),
    Color(0xFF86B82E),
    Color(0xFFB8F04A),
    Color(0xFFFFD166),
    Color(0xFFFF9F5C),
    Color(0xFFFF7F5C),
)
