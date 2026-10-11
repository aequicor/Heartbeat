package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlin.uuid.Uuid

/**
 * Caller validates route/account ownership through the journal and revokes live hosted work before entering.
 * A false stop, cancellation, or storage failure retains the fence. No storage mutex covers OS waits.
 * This boundary never starts/resumes a native thread or retries the original prompt.
 */
internal class PiStoredTurnStop(private val journal: PiTurnJournal, private val processes: PiProcesses) {
    suspend fun stop(request: RequestId, turn: TurnId? = null): PiTurnRecord? {
        val before = journal.restore() ?: return null
        val settled = before.last?.takeIf {
            before.stopping == null && before.active?.matches(request, turn) != true && it.matches(request, turn)
        }
        return settled ?: journal.fenceStop(request, turn)?.let { stopFenced(it) }
    }

    private suspend fun stopFenced(stopping: PiTurnRecord): PiTurnRecord? {
        var observed = checkNotNull(stopping.processOwner)
        val inspection = Uuid.random().toString()
        val isStopped = processes.stop(observed, { journal.inspectStop(stopping, inspection) }) { discovered ->
            journal.observeStop(stopping, inspection, discovered)?.also { observed = it }
        }
        return if (isStopped) journal.stopped(stopping, observed) else null
    }
}
