package icu.intervalsync.karoo.account

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import icu.intervalsync.karoo.R

/**
 * The goal, events and other context the rider saved to their Intervalsync account,
 * in their own words. Shown only while connected.
 */
@Composable
fun TrainingContextSection(account: AccountController) {
    val state by account.state.collectAsState()
    val context by produceState<TrainingContext?>(initialValue = null, state) {
        value = if (state is AccountState.Paired) account.trainingContext() else null
    }
    val shown = context ?: return
    val sections = listOf(
        R.string.context_goal to shown.goal,
        R.string.context_events to shown.events,
        R.string.context_constraints to shown.constraints,
        R.string.context_preferences to shown.preferences,
        R.string.context_notes to shown.notes,
    ).filter { it.second != null }

    Text(stringResource(R.string.context_title), style = MaterialTheme.typography.titleMedium)
    if (sections.isEmpty()) {
        Text(stringResource(R.string.context_empty), style = MaterialTheme.typography.bodyMedium)
    }
    sections.forEach { (label, text) ->
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(text.orEmpty(), style = MaterialTheme.typography.bodyMedium)
    }
}
