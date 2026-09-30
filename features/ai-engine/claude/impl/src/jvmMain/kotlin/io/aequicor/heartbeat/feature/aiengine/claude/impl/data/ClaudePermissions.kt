package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.accepts
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

/** Pending decisions belong to one native turn; history replay and stale decisions never authorize tools. */
internal class ClaudePermissions(
    private val observer: ClaudeTurnObserver,
    private val history: ClaudeHistory,
    private val isActive: () -> Boolean,
    private val update: (ActiveSessionState) -> Unit,
    private val persist: suspend () -> Unit,
) : AgentToolPermissions {
    private val lock = Any()
    private val pending = linkedMapOf<PermissionRequestId, Pending>()
    private var isClosed = false

    override suspend fun request(approval: AgentToolApproval): Boolean {
        val item = synchronized(lock) {
            if (isClosed || !isActive()) return false
            val request = PermissionRequest(
                PermissionRequestId(UUID.randomUUID().toString()),
                observer.turn.id,
                approval.title,
                listOf(PermissionOption(ALLOW, "Allow once"), PermissionOption(DENY, "Deny")),
                approval.description,
            )
            Pending(request).also {
                pending[request.id] = it
                history.publish { checkpoint -> SessionEvent.PermissionRequested(checkpoint, request) }
                publish()
            }
        }
        persist()
        return try {
            item.answer.await() && isActive()
        } finally {
            synchronized(lock) {
                if (pending.remove(item.request.id) != null && !isClosed && isActive()) publish()
            }
        }
    }

    suspend fun respond(decision: PermissionDecision) {
        synchronized(lock) {
            val item = pending[decision.request]?.takeIf { it.request.accepts(decision) }
            if (isClosed || !isActive() || item == null) return
            pending.remove(decision.request)
            observer.permissionResolved(decision.request)
            publish()
            item.answer.complete(decision.option == ALLOW)
        }
        persist()
    }

    /** Revocation unblocks bridge calls even when they execute outside the native process coroutine. */
    fun close() = synchronized(lock) {
        isClosed = true
        pending.values.forEach { it.answer.complete(false) }
        pending.clear()
    }

    private fun publish() {
        val requests = pending.values.map { it.request }
        update(
            if (requests.isEmpty()) {
                ActiveSessionState.Running(observer.turn)
            } else {
                ActiveSessionState.AwaitingUserAction(observer.turn, requests)
            },
        )
    }
}

private data class Pending(
    val request: PermissionRequest,
    val answer: CompletableDeferred<Boolean> = CompletableDeferred(),
)
private val ALLOW = PermissionOptionId("allow")
private val DENY = PermissionOptionId("deny")
