package icu.intervalsync.karoo.account

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import icu.intervalsync.karoo.R
import icu.intervalsync.karoo.SettingsStore
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Training hours per week as bars, and critical power and W′ as lines, over the
 * last 6 or 12 weeks from Intervals.icu. Shown only while connected.
 */
@Composable
fun TrendsSection(account: AccountController, settings: SettingsStore) {
    val state by account.state.collectAsState()
    val trends by produceState<Trends?>(initialValue = null, state) {
        value = if (state is AccountState.Paired) account.trends() else null
    }
    var span by remember { mutableIntStateOf(settings.trendWeeks) }
    val all = trends?.weeks?.takeIf { it.isNotEmpty() } ?: return
    val weeks = all.takeLast(span)

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (option in SPANS) {
            val select = {
                span = option
                settings.trendWeeks = option
            }
            val label = stringResource(R.string.trends_weeks, option)
            if (option == span) {
                Button(onClick = select, modifier = Modifier.weight(1f), contentPadding = SpanPadding) { Text(label, maxLines = 1) }
            } else {
                OutlinedButton(onClick = select, modifier = Modifier.weight(1f), contentPadding = SpanPadding) { Text(label, maxLines = 1) }
            }
        }
    }
    HoursBars(weeks)
    TrendLine(stringResource(R.string.trend_cp_title), weeks.map { it.cpW }) {
        stringResource(R.string.watts, it.roundToInt())
    }
    TrendLine(stringResource(R.string.trend_w_prime_title), weeks.map { it.wPrimeJ }) {
        stringResource(R.string.kilojoules, decimal(it / 1000))
    }
    HorizontalDivider()
}

/** One bar per week of training hours; the last bar is the current week, still filling up. */
@Composable
private fun HoursBars(weeks: List<TrendWeek>) {
    // The current week is partial, so it stays out of the average
    val complete = weeks.dropLast(1).ifEmpty { weeks }
    val tallest = weeks.maxOf { it.hours }.coerceAtLeast(1.0)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = LabelColor, fontSize = 11.sp)
    val context = LocalContext.current

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(stringResource(R.string.consistency_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.consistency_average, decimal(complete.sumOf { it.hours } / complete.size)), style = MaterialTheme.typography.titleMedium)
    }
    Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
        val labelHeight = 16.dp.toPx()
        val slot = size.width / weeks.size
        val barWidth = slot * 0.72f
        val chartHeight = size.height - labelHeight
        weeks.forEachIndexed { index, week ->
            val left = index * slot + (slot - barWidth) / 2
            val barHeight = (chartHeight * (week.hours / tallest)).toFloat()
            if (barHeight > 0) {
                drawRoundRect(
                    color = if (index == weeks.lastIndex) CurrentWeekColor else WeekColor,
                    topLeft = Offset(left, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
            // Hours above each bar while there is room for them
            if (weeks.size <= LABELLED_BARS) {
                val label = measurer.measure(decimal(week.hours), labelStyle)
                drawText(label, topLeft = Offset(left + (barWidth - label.size.width) / 2, size.height - barHeight - labelHeight))
            }
        }
        drawLine(BaselineColor, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), strokeWidth = 1.dp.toPx())
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            WeekFormat.parse(weeks.first().weekStart)?.let {
                DateUtils.formatDateTime(context, it.time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)
            } ?: weeks.first().weekStart,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(R.string.consistency_this_week), style = MaterialTheme.typography.bodySmall)
    }
}

/** A title, the latest value, and a line through the weeks that have a value. */
@Composable
private fun TrendLine(title: String, values: List<Double?>, format: @Composable (Double) -> String) {
    val present = values.filterNotNull()
    val latest = present.lastOrNull() ?: return
    // These move by a few percent, so the scale is their own range rather than zero-based
    val low = present.min()
    val high = present.max().let { if (it > low) it else low + 1 }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(format(latest), style = MaterialTheme.typography.titleMedium)
    }
    Text(stringResource(R.string.trend_from, format(present.first())), style = MaterialTheme.typography.bodySmall)
    Canvas(modifier = Modifier.fillMaxWidth().height(56.dp)) {
        val inset = 4.dp.toPx()
        // Points sit at bar centres so the lines read against the bars above
        val slot = size.width / values.size
        fun point(index: Int, value: Double) = Offset(
            x = index * slot + slot / 2,
            y = inset + (size.height - 2 * inset) * (1 - ((value - low) / (high - low)).toFloat()),
        )
        val line = Path()
        var started = false
        var last = Offset.Zero
        values.forEachIndexed { index, value ->
            if (value == null) return@forEachIndexed
            last = point(index, value)
            if (started) line.lineTo(last.x, last.y) else line.moveTo(last.x, last.y)
            started = true
            drawCircle(LineColor, radius = 2.dp.toPx(), center = last)
        }
        drawLine(BaselineColor, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), strokeWidth = 1.dp.toPx())
        drawPath(line, LineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(LineColor, radius = 3.5.dp.toPx(), center = last)
    }
}

private fun decimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

private val SPANS = listOf(6, 12)
private val SpanPadding = PaddingValues(horizontal = 8.dp)
private const val LABELLED_BARS = 6
private val WeekFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
private val WeekColor = Color(0xFF86B82E)
private val CurrentWeekColor = Color(0xFFB8F04A)
private val LineColor = Color(0xFFB8F04A)
private val LabelColor = Color(0xFFCAC4D0)
private val BaselineColor = Color(0x40FFFFFF)
