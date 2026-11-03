package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTrees
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Restricts a read view to its checked route and revalidates before delivering any result. */
internal class CheckedSessionTrees(
    private val native: SessionTrees,
    private val expectedRoot: SessionRef,
    private val expectedAccess: SessionTreeAccess,
    private val recheck: suspend () -> Unit,
) : SessionTrees {
    override fun observe(root: SessionRef, access: SessionTreeAccess): Flow<SessionTreeSnapshot> = flow {
        requireAccess(root, access)
        recheck()
        native.observe(root, access).collect { snapshot ->
            recheck()
            emit(snapshot)
        }
    }

    override suspend fun history(root: SessionRef, ref: SessionRef, access: SessionTreeAccess): SessionHistory {
        requireAccess(root, access)
        if (ref.engine != root.engine || ref.source != root.source) fail(InvalidRequest)
        recheck()
        val history = native.history(root, ref, access)
        recheck()
        return CheckedHistory(history, recheck)
    }

    private fun requireAccess(root: SessionRef, access: SessionTreeAccess) {
        if (root != expectedRoot || access != expectedAccess) fail(InvalidRequest)
    }
}

private class CheckedHistory(private val native: SessionHistory, private val recheck: suspend () -> Unit) :
    SessionHistory {
    override suspend fun page(request: HistoryPageRequest): HistoryPage {
        recheck()
        val page = native.page(request)
        recheck()
        return page
    }

    override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = flow {
        recheck()
        native.watch(after).collect { event ->
            recheck()
            emit(event)
        }
    }
}
