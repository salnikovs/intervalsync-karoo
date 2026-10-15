package icu.intervalsync.karoo.account

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import icu.intervalsync.karoo.R
import icu.intervalsync.karoo.core.CadenceTargets
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Workouts planned on the rider's Intervals.icu calendar for today and the next 7 days.
 */
@Composable
fun PlanSection(account: AccountController) {
    val state by account.state.collectAsState()
    if (state !is AccountState.Paired) {
        Text(stringResource(R.string.plan_not_connected), style = MaterialTheme.typography.bodyMedium)
        return
    }
    // null while loading; the fetch result otherwise, itself null when it failed with nothing cached
    val loaded by produceState<Result<List<PlannedWorkout>?>?>(initialValue = null, state) {
        value = Result.success(account.plannedWorkouts())
    }
    val workouts = loaded?.getOrNull()
    when {
        loaded == null -> Text(stringResource(R.string.plan_loading), style = MaterialTheme.typography.bodyMedium)
        workouts == null -> Text(stringResource(R.string.plan_failed), style = MaterialTheme.typography.bodyMedium)
        workouts.isEmpty() -> Text(stringResource(R.string.plan_empty), style = MaterialTheme.typography.bodyMedium)
        else -> workouts.groupBy { it.date }.entries.forEachIndexed { index, (date, onDay) ->
            if (index > 0) HorizontalDivider()
            Text(dayLabel(date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            onDay.forEach { WorkoutRow(it) }
        }
    }
}

@Composable
private fun WorkoutRow(workout: PlannedWorkout) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp)) {
        Text(workout.name, style = MaterialTheme.typography.titleMedium)
        val details = listOfNotNull(
            workout.durationSeconds?.let { stringResource(R.string.summary_duration, it / 3600, it % 3600 / 60) },
            workout.load?.let { stringResource(R.string.plan_load, it) },
            workout.type,
        )
        if (details.isNotEmpty()) {
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        }
        // Karoo's own workout sync drops cadence targets, so they are spelled out here
        val cadence = remember(workout.description) { CadenceTargets.from(workout.description) }
        if (cadence.isNotEmpty()) {
            Text(
                stringResource(R.string.plan_cadence),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
            cadence.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        workout.description?.let {
            // Tap to read a long description in full
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    OpenInWorkouts()
}

/**
 * Opens Karoo's own Workouts list, where workouts planned on Intervals.icu arrive through
 * Karoo's Intervals.icu connection and can be started. karoo-ext has no way to open one
 * workout, so this lands on the list. Hidden on a Karoo without that screen.
 */
@Composable
private fun OpenInWorkouts() {
    val context = LocalContext.current
    if (!context.canOpenWorkouts()) return
    OutlinedButton(onClick = { context.openWorkouts() }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.plan_open_workouts))
    }
}

private fun Context.canOpenWorkouts(): Boolean = WorkoutsIntent.resolveActivity(packageManager) != null

private fun Context.openWorkouts() {
    try {
        startActivity(Intent(WorkoutsIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Timber.w(e, "Karoo's Workouts screen isn't available")
    }
}

/** Karoo's Workouts list; the action is the system's own, not part of karoo-ext. */
private val WorkoutsIntent = Intent("io.hammerhead.intent.action.ADD_WORKOUT")

@Composable
private fun dayLabel(date: String): String {
    val day = DayFormat.parse(date) ?: return date
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return when ((day.time - today) / DateUtils.DAY_IN_MILLIS) {
        0L -> stringResource(R.string.plan_today)
        1L -> stringResource(R.string.plan_tomorrow)
        else -> DateUtils.formatDateTime(
            LocalContext.current,
            day.time,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_NO_YEAR,
        )
    }
}

private const val COLLAPSED_LINES = 3
private val DayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
