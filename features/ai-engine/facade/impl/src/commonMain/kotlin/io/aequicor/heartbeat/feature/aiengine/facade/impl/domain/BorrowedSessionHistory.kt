package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Read-only history borrowed from one handle. Closing the owner ends subscriptions with SessionClosed so readers
 * resolve history again instead of waiting forever on a retired adapter's journal. Cancelling a reader only
 * releases its subscriptions; it never closes the owner. Checkpoints are never reused across replacement handles.
 */
internal class BorrowedSessionHistory(private val owner: ActiveSession, private val history: SessionHistory) :
    SessionHistory {
    override suspend fun page(request: HistoryPageRequest) = run {
        if (owner.state.value.isClosingOrClosed()) closed()
        history.page(request)
    }

    override fun watch(after: HistoryCheckpoint) = channelFlow {
        val lifecycle = launch(start = CoroutineStart.UNDISPATCHED) {
            owner.state.first { it.isClosingOrClosed() }
            closed()
        }
        try {
            history.watch(after).collect { send(it) }
        } finally {
            lifecycle.cancel()
        }
    }

    private fun closed(): Nothing = fail(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed))
}

private fun ActiveSessionState.isClosingOrClosed(): Boolean =
    this is ActiveSessionState.Closing || this == ActiveSessionState.Closed
