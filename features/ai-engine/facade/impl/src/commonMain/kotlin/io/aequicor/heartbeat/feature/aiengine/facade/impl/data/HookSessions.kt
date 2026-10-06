package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionLifecycle
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Atomic ownership bookkeeping happens before asynchronous observation delivery. */
internal class HookSessions {
    private val log = Log.tag("SessionHooks")
    private val state = MutableStateFlow(Snapshot())

    internal val turnCount: Int get() = state.value.turns.size

    fun isOpen(context: SessionHookContext): Boolean = state.value.owners[context.owner]?.session == context.session

    @HighFrequency
    fun bind(context: SessionHookContext) {
        log.v { "Bind hook turn" }
        val turn = context.turn ?: return
        state.update { current ->
            if (current.owners[context.owner]?.session != context.session) {
                current
            } else {
                val key = context.session to turn
                val previous = current.turns[key]
                check(previous == null || previous.context == context) { "Hook turn already has an owner" }
                current.copy(turns = current.turns + (key to (previous ?: Tracked(context))))
            }
        }
    }

    @HighFrequency
    fun release(session: SessionRef, turn: TurnId, isRejected: Boolean) {
        log.v { "Release hook turn" }
        state.update { current ->
            val key = session to turn
            val tracked = current.turns[key] ?: return@update current
            val turns = if (!isRejected && !tracked.isFinished && tracked.context.owner in current.owners) {
                current.turns + (key to tracked.copy(isActive = false))
            } else {
                current.turns - key
            }
            current.copy(turns = turns)
        }
    }

    fun context(session: SessionRef, request: RequestId?, turn: TurnId?): SessionHookContext? =
        state.value.turns.values.firstOrNull {
            it.isActive && it.context.session == session &&
                if (request != null) it.context.request == request else turn != null && it.context.turn == turn
        }?.context

    @HighFrequency
    fun accept(event: SessionLifecycle): Boolean {
        log.v { "Observe hook lifecycle ${event::class.simpleName.orEmpty()}" }
        while (true) {
            val current = state.value
            val next = current.accept(event) ?: return false
            if (state.compareAndSet(current, next)) return true
        }
    }
}

private data class Snapshot(
    val owners: Map<SessionOwner, SessionHookContext> = emptyMap(),
    val turns: Map<Pair<SessionRef, TurnId>, Tracked> = emptyMap(),
) {
    fun accept(event: SessionLifecycle): Snapshot? {
        val context = event.context
        return when (event) {
            is SessionLifecycle.Opened -> if (context.owner in owners) {
                null
            } else {
                copy(
                    owners = owners + (context.owner to context),
                )
            }

            is SessionLifecycle.Closed -> if (owners[context.owner]?.session != context.session) {
                null
            } else {
                copy(
                    owners = owners - context.owner,
                    turns = turns.filterValues { it.context.owner != context.owner || it.isActive },
                )
            }

            is SessionLifecycle.TurnStarted, is SessionLifecycle.TurnFinished,
            is SessionLifecycle.PermissionRequested,
            -> acceptTurn(event)
        }
    }

    private fun acceptTurn(event: SessionLifecycle): Snapshot? {
        val context = event.context
        val key = context.session to (context.turn ?: return null)
        val tracked = turns[key] ?: return null
        if (tracked.context != context || tracked.isFinished) return null
        val next = when (event) {
            is SessionLifecycle.TurnStarted -> if (tracked.isAccepted) null else tracked.copy(isAccepted = true)
            is SessionLifecycle.TurnFinished -> if (!tracked.isAccepted) null else tracked.copy(isFinished = true)
            is SessionLifecycle.PermissionRequested -> tracked.permission(event)
            is SessionLifecycle.Opened, is SessionLifecycle.Closed -> null
        } ?: return null
        return copy(turns = if (next.isFinished && !next.isActive) turns - key else turns + (key to next))
    }
}

private data class Tracked(
    val context: SessionHookContext,
    val isActive: Boolean = true,
    val isAccepted: Boolean = false,
    val isFinished: Boolean = false,
    val permissions: Set<PermissionRequestId> = emptySet(),
) {
    fun permission(event: SessionLifecycle.PermissionRequested): Tracked? =
        if (!isAccepted || event.permission.id in permissions || event.permission.turn != context.turn) {
            null
        } else {
            copy(permissions = permissions + event.permission.id)
        }
}
