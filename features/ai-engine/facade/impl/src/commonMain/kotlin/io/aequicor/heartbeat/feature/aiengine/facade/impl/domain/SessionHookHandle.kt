package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionOutput
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionLifecycle
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.SessionHooks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Observes only this handle's submissions, even when its native session has other handles. */
internal class SessionHookHandle(
    private val hooks: SessionHooks,
    private val context: SessionHookContext,
    private val finishUnaccepted: (TurnId) -> Unit,
) {
    private val log = Log.tag("SessionHooks")
    private val lock = Mutex()
    private val turns = mutableMapOf<TurnId, OwnedTurn>()

    fun start(scope: CoroutineScope, machine: Machine<ActiveSessionState, ActiveSessionIntent, ActiveSessionOutput>) {
        hooks.observe(SessionLifecycle.Opened(context))
        scope.coroutineContext[Job]?.invokeOnCompletion { closed() }
        // Accepted/Finished can both be emitted before native send returns. Subscribe before any submission.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            machine.outputs.collect { output ->
                when (output) {
                    is ActiveSessionOutput.Accepted -> accepted(output.turn)

                    is ActiveSessionOutput.Finished -> finished(output.turn)

                    // Submission failure may mean unknown native acceptance; keep interception until reconciliation.
                    is ActiveSessionOutput.SubmissionFailed -> Unit
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            machine.state.collect { state -> if (state is ActiveSessionState.AwaitingUserAction) permissions(state) }
        }
    }

    suspend fun prepare(request: PromptRequest, turn: Turn): PromptRequest {
        val bound = context.copy(request = request.id, turn = turn.id)
        val text = request.parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }
        val addition = if (text.startsWith('/')) null else hooks.beforePrompt(bound, text)
        lock.withLock { turns[turn.id] = OwnedTurn(bound) }
        hooks.bindTurn(bound)
        return if (addition.isNullOrBlank()) request else request.append(addition)
    }

    /** The machine refused submission before starting native work. */
    suspend fun refused(turn: TurnId) {
        lock.withLock { turns.remove(turn) }
        hooks.releaseTurn(context.session, turn, isRejected = true)
    }

    fun closed() {
        hooks.observe(SessionLifecycle.Closed(context))
    }

    @HighFrequency
    private suspend fun accepted(turn: Turn) = lock.withLock {
        val owned = turns[turn.id] ?: return@withLock
        if (owned.context.request != turn.request || owned.isAccepted) return@withLock
        log.v { "Observe accepted hook turn" }
        turns[turn.id] = owned.copy(isAccepted = true, pending = emptyMap())
        hooks.observe(SessionLifecycle.TurnStarted(owned.context))
        owned.pending.values.forEach { hooks.observe(SessionLifecycle.PermissionRequested(owned.context, it)) }
    }

    @HighFrequency
    private suspend fun finished(turn: Turn) {
        val owned = lock.withLock { turns.remove(turn.id) } ?: return
        if (owned.context.request != turn.request) return
        if (owned.isAccepted) {
            log.v { "Observe finished hook turn" }
            turn.outcome?.let { hooks.observe(SessionLifecycle.TurnFinished(owned.context, it)) }
        } else {
            // Reconciliation ended an ambiguous submission; the caller never received its facade turn id.
            log.v { "Release recovered unaccepted hook turn" }
            finishUnaccepted(turn.id)
        }
    }

    @HighFrequency
    private suspend fun permissions(state: ActiveSessionState.AwaitingUserAction) = lock.withLock {
        val owned = turns[state.turn.id] ?: return@withLock
        if (owned.context.request != state.turn.request) return@withLock
        if (owned.isAccepted) {
            state.requests.forEach { hooks.observe(SessionLifecycle.PermissionRequested(owned.context, it)) }
        } else {
            log.v { "Buffer hook permissions before acceptance" }
            turns[state.turn.id] = owned.copy(pending = owned.pending + state.requests.associateBy { it.id })
        }
    }
}

private data class OwnedTurn(
    val context: SessionHookContext,
    val isAccepted: Boolean = false,
    val pending: Map<PermissionRequestId, PermissionRequest> = emptyMap(),
) {
    override fun toString(): String = "OwnedTurn"
}

private fun PromptRequest.append(addition: String): PromptRequest {
    val last = parts.indexOfLast { it is ContentPart.Text }
    return if (last < 0) {
        copy(parts = parts + ContentPart.Text(addition))
    } else {
        copy(
            parts = parts.mapIndexed { index, part ->
                if (index == last && part is ContentPart.Text) ContentPart.Text(part.text + "\n\n" + addition) else part
            },
        )
    }
}
