package icu.intervalsync.karoo.summary

import icu.intervalsync.karoo.account.AccountController
import icu.intervalsync.karoo.account.AccountState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends the last ride's report to Intervalsync as soon as it can: when the ride ends, and
 * again whenever the app or the extension starts while it hasn't gone. A report sent again
 * for the same ride replaces the earlier one, so picking a feel afterwards just resends it.
 */
object RideReporter {
    private val sending = Mutex()

    /** True when nothing is left to send; false when a report is still waiting. */
    suspend fun sendPending(account: AccountController, store: SummaryStore): Boolean = sending.withLock {
        val summary = store.read() ?: return true
        if (summary.sent) return true
        if (account.state.value !is AccountState.Paired) return false
        if (!account.sendRideReport(summary.toReport())) return false
        // Written back only if the rider didn't change it while this was in flight
        if (store.read() == summary) store.write(summary.copy(sent = true))
        return store.read()?.sent ?: true
    }
}
