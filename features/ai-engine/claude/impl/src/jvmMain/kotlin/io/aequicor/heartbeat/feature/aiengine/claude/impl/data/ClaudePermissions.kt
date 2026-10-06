package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.logging.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Pending decisions belong to one native turn; history replay and stale decisions never authorize tools. */
internal class ClaudePermissions(
    private val observer: ClaudeTurnObserver,
    private val history: ClaudeHistory,
    private val isActive: () -> Boolean,
    private val update: (ActiveSessionState) -> Unit,
    private val persist: suspend () -> Unit,
    private val canApprove: () -> Boolean,
) : AgentToolPermissions {
    private val log = Log.tag("ClaudePermissions")
    private val lock = Any()
    private val pending = linkedMapOf<PermissionRequestId, Pending>()
    private var isClosed = false

    override suspend fun request(approval: AgentToolApproval): Boolean {
        val item = synchronized(lock) {
            if (isClosed || !isActive() || !canApprove()) return false
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
        return try {
            persist()
            item.answer.await() && isActive() && canApprove()
        } finally {
            val isWithdrawn = synchronized(lock) {
                if (pending[item.request.id] !== item) {
                    false
                } else {
                    pending.remove(item.request.id)
                    observer.permissionResolved(item.request.id)
                    item.answer.complete(false)
                    if (!isClosed && isActive()) publish()
                    true
                }
            }
            if (isWithdrawn) withContext(NonCancellable) { persistWithdrawal() }
        }
    }

    suspend fun respond(decision: PermissionDecision) {
        synchronized(lock) {
            val item = pending[decision.request]?.takeIf { it.request.accepts(decision) } ?: return
            if (isClosed || !isActive() || !canApprove()) return
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
        pending.values.forEach {
            observer.permissionResolved(it.request.id)
            it.answer.complete(false)
        }
        pending.clear()
    }

    /** Declines unanswered requests on final observation detach while accepted read/Full work remains active. */
    fun dismiss() = synchronized(lock) {
        if (pending.isEmpty()) return@synchronized
        pending.values.forEach {
            observer.permissionResolved(it.request.id)
            it.answer.complete(false)
        }
        pending.clear()
        if (!isClosed && isActive()) publish()
    }

    private suspend fun persistWithdrawal() {
        val isSaved = withTimeoutOrNull(WITHDRAWAL_TIMEOUT_MS) {
            try {
                persist()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e.redacted()) { "Failed to persist permission withdrawal" }
                false
            }
        }
        if (isSaved == null) log.w { "Permission withdrawal persistence timed out" }
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

private const val WITHDRAWAL_TIMEOUT_MS = 2_000L
