package icu.intervalsync.karoo.account

import android.text.format.DateUtils
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import icu.intervalsync.karoo.R
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
private fun rememberCoach(account: AccountController): Coach? {
    val state by account.state.collectAsState()
    val coach by produceState<Coach?>(initialValue = null, state) {
        value = if (state is AccountState.Paired) account.coach() else null
    }
    return coach
}

/**
 * The brief each coach or team space shares, and its latest updates. Always under the
 * space's own name, since these are other members' words rather than the rider's.
 */
@Composable
fun SharedSpacesSection(account: AccountController) {
    val state by account.state.collectAsState()
    val spaces by produceState<List<SharedSpace>?>(initialValue = null, state) {
        value = if (state is AccountState.Paired) account.sharedSpaces() else null
    }
    val context = LocalContext.current
    spaces.orEmpty().forEach { space ->
        HorizontalDivider()
        Text(stringResource(if (space.coach) R.string.space_coach else R.string.space_team, space.name), style = MaterialTheme.typography.titleMedium)
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
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(comment.text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Today's brief and targets from the assistant, at the top of the Plan tab. Nothing on days without them. */
@Composable
fun TodaySection(account: AccountController) {
    val coach = rememberCoach(account) ?: return
    val today = Coach.today()
    val brief = coach.briefFor(today)
    val targets = coach.targetsFor(today)?.takeIf { it.powerMaxW != null || it.hrMaxBpm != null || it.note != null }
    if (brief == null && targets == null) return

    if (brief != null) {
        Text(stringResource(R.string.brief_title), style = MaterialTheme.typography.titleMedium)
        Text(brief.text, style = MaterialTheme.typography.bodyMedium)
    }
    if (targets != null) {
        Text(stringResource(R.string.targets_title), style = MaterialTheme.typography.titleMedium)
        targets.powerMaxW?.let { Text(stringResource(R.string.targets_power, it), style = MaterialTheme.typography.bodyLarge) }
        targets.hrMaxBpm?.let { Text(stringResource(R.string.targets_hr, it), style = MaterialTheme.typography.bodyLarge) }
        targets.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
    HorizontalDivider()
}

private const val SHOWN_COMMENTS = 3
private val DayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
