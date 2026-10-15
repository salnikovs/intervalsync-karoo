package icu.intervalsync.karoo.account

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import icu.intervalsync.karoo.R
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

@Composable
private fun rememberCoach(account: AccountController): Coach? {
    val state by account.state.collectAsState()
    val coach by produceState<Coach?>(initialValue = null, state) {
        value = if (state is AccountState.Paired) account.coach() else null
    }
    return coach
}

/**
 * The coach and teams the rider belongs to, on the Settings tab. Each is its name until
 * tapped, then the brief its members share and its latest updates: other people's words,
 * so always under the space's name. Nothing without any.
 */
@Composable
fun MembershipsSection(account: AccountController) {
    val state by account.state.collectAsState()
    val spaces by produceState<List<SharedSpace>?>(initialValue = null, state) {
        value = if (state is AccountState.Paired) account.sharedSpaces() else null
    }
    val shown = spaces?.takeIf { it.isNotEmpty() } ?: return
    Text(stringResource(R.string.memberships_title), style = MaterialTheme.typography.titleMedium)
    shown.sortedBy { !it.coach }.forEach { space -> SpaceRow(space) }
    HorizontalDivider()
}

@Composable
private fun SpaceRow(space: SharedSpace) {
    var expanded by rememberSaveable(space.name) { mutableStateOf(false) }
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(if (space.coach) R.string.membership_coach else R.string.membership_team, space.name),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.bodyLarge)
    }
    if (!expanded) return
    Labelled(R.string.context_goal, space.goal)
    Labelled(R.string.space_focus, space.focus)
    if (space.priorities.isNotEmpty()) {
        Text(stringResource(R.string.space_priorities), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        space.priorities.forEach { Text(stringResource(R.string.space_bullet, it), style = MaterialTheme.typography.bodyMedium) }
    }
    Labelled(R.string.context_constraints, space.constraints)
    if (space.updates.isNotEmpty()) {
        Text(stringResource(R.string.space_updates), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        space.updates.forEach { update ->
            val date = DayFormat.parse(update.date)?.let {
                DateUtils.formatDateTime(context, it.time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)
            } ?: update.date
            Text(listOf(update.author, date).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
            Text(update.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Labelled(label: Int, text: String?) {
    if (text == null) return
    Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

/** The assistant's latest notes on rides and weeks, under the training context. */
@Composable
fun CoachCommentsSection(account: AccountController) {
    val comments = rememberCoach(account)?.comments?.takeIf { it.isNotEmpty() } ?: return
    val context = LocalContext.current

    HorizontalDivider()
    Text(stringResource(R.string.coach_title), style = MaterialTheme.typography.titleMedium)
    comments.take(SHOWN_COMMENTS).forEach { comment ->
        val date = DayFormat.parse(comment.date)?.let {
            DateUtils.formatDateTime(context, it.time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)
        } ?: comment.date
        Text(
            when {
                comment.week -> stringResource(R.string.coach_week, date)
                comment.title != null -> "$date · ${comment.title}"
                else -> date
            } + (comment.author?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(comment.text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Today's brief, whoever wrote it, at the top of the Training tab: four lines until
 * tapped, then in full. Nothing on days without one.
 */
@Composable
fun BriefCard(account: AccountController) {
    val brief = rememberCoach(account)?.briefFor(Coach.today()) ?: return
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(briefTitle(brief), style = MaterialTheme.typography.titleMedium)
        Text(
            brief.text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else BRIEF_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
    HorizontalDivider()
}

/**
 * This morning's HRV, resting HR and sleep from Intervals.icu, as three numbers under the
 * brief. Nothing until today's are in.
 */
@Composable
fun WellnessRow(account: AccountController) {
    val state by account.state.collectAsState()
    val wellness by produceState<Wellness?>(initialValue = null, state) {
        value = if (state is AccountState.Paired) account.wellness() else null
    }
    val today = wellness?.takeIf { it.date == Coach.today() && !it.isEmpty } ?: return
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        WellnessValue(stringResource(R.string.wellness_hrv), today.hrvMs?.let { stringResource(R.string.wellness_ms, it.roundToInt()) })
        WellnessValue(stringResource(R.string.wellness_rhr), today.restingHr?.let { stringResource(R.string.wellness_bpm, it) })
        WellnessValue(
            stringResource(R.string.wellness_sleep),
            today.sleepSeconds?.let { stringResource(R.string.wellness_sleep_value, it / 3600, it % 3600 / 60) },
        )
    }
    today.source?.let { Text(stringResource(R.string.wellness_source, it), style = MaterialTheme.typography.bodySmall) }
    HorizontalDivider()
}

@Composable
private fun WellnessValue(label: String, value: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value ?: stringResource(R.string.no_value), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun briefTitle(brief: RideBrief): String =
    stringResource(R.string.brief_title) + (brief.author?.let { " · $it" } ?: "")

private const val BRIEF_LINES = 4

/** Today's targets, at the top of the Plan tab; the brief is on the Training tab. Nothing on days without them. */
@Composable
fun TodaySection(account: AccountController) {
    val targets = rememberCoach(account)?.targetsFor(Coach.today())
        ?.takeIf { it.powerMaxW != null || it.hrMaxBpm != null || it.note != null } ?: return

    Text(stringResource(R.string.targets_title), style = MaterialTheme.typography.titleMedium)
    targets.powerMaxW?.let { Text(stringResource(R.string.targets_power, it), style = MaterialTheme.typography.bodyLarge) }
    targets.hrMaxBpm?.let { Text(stringResource(R.string.targets_hr, it), style = MaterialTheme.typography.bodyLarge) }
    targets.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    HorizontalDivider()
}

private const val SHOWN_COMMENTS = 3
private val DayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
