package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Permission publication and withdrawal share the hosted job lifetime, including cancellation while publishing. */
internal class CodexHostedPermissions(private val session: CodexSession, private val hasLease: () -> Boolean) {
    private val requests = mutableMapOf<PermissionRequestId, PermissionRequest>()
    private val answers = mutableMapOf<PermissionRequestId, CompletableDeferred<PermissionDecision?>>()
    val pending: Collection<PermissionRequest> get() = requests.values

    suspend fun awaitDecision(request: PermissionRequest): PermissionDecision? {
        if (session.machine.state.value.codexCurrentTurn()?.id != request.turn ||
            session.hostedJobs.isClosed(request.turn) || !hasLease()
        ) {
            return null
        }
        val answer = CompletableDeferred<PermissionDecision?>()
        answers[request.id] = answer
        requests[request.id] = request
        return try {
            if (session.machine.send(ActiveSessionIntent.Internal.PermissionNeeded(request)) == SendResult.Accepted) {
                session.history.publish { SessionEvent.PermissionRequested(it, request) }
                answer.await()
            } else {
                null
            }
        } finally {
            answers.remove(request.id)
            requests.remove(request.id)
            withContext(NonCancellable) {
                session.machine.send(ActiveSessionIntent.Internal.PermissionResolved(request.turn, request.id))
            }
        }
    }

    fun decide(decision: PermissionDecision) {
        val answer = answers.remove(decision.request) ?: protocolFailure()
        answer.complete(decision)
    }

    fun release() = answers.values.forEach { it.complete(null) }
    fun clear(turn: TurnId? = null) = requests.entries.removeAll { turn == null || it.value.turn == turn }
}
