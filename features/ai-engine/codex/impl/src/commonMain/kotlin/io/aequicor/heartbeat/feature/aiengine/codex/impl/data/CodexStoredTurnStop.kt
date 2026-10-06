package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlin.uuid.Uuid

/**
 * Caller validates route/account ownership through the journal and revokes live hosted work before entering.
 * A false stop, cancellation, or storage failure retains the fence. No storage mutex covers OS waits.
 * This boundary never starts/resumes a native thread or retries the original prompt.
 */
internal class CodexStoredTurnStop(private val journal: CodexTurnJournal, private val launch: PreparedCodexLaunch) {
    suspend fun stop(request: RequestId, turn: TurnId? = null): CodexTurnRecord? {
        val before = journal.restore() ?: return null
        val settled = before.last?.takeIf { before.stopping == null && it.matches(request, turn) }
        return settled ?: journal.fenceStop(request, turn)?.let { stopFenced(it) }
    }

    private suspend fun stopFenced(stopping: CodexTurnRecord): CodexTurnRecord? {
        var observed = checkNotNull(stopping.processOwner)
        val inspection = Uuid.random().toString()
        val isStopped = launch.stop(observed, { journal.inspectStop(stopping, inspection) }) { discovered ->
            journal.observeStop(stopping, inspection, discovered)?.also { observed = it }
        }
        return if (isStopped) journal.stopped(stopping, observed) else null
    }
}
