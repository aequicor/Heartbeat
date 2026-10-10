package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AcceptsImages
import io.aequicor.heartbeat.feature.aiengine.facade.api.AcceptsResources
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AppliesTrustLevels
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.RestoresSessionTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Releasing this observation lease cannot cancel the shared machine or its native turn. */
internal class CodexLease(private val session: CodexSession) :
    ActiveSession,
    SendsPrompts,
    CancelsTurns,
    RequestsPermissions,
    AppliesTrustLevels,
    RestoresSessionTurns,
    SessionHistory {
    private val log = Log.tag("CodexLease")
    override val ref = session.ref
    override val route = session.route
    private val mutableState = MutableStateFlow(session.machine.state.value)
    override val state: StateFlow<ActiveSessionState> = mutableState.asStateFlow()
    override val features: EngineFeatures = CodexFeatures(
        this,
        session.contextUsage,
        session.contextRevision,
        object : AcceptsImages {
            override val mediaTypes: Set<String> get() = session.runtime.inputSupport(
                session.target.model,
            ).imageMediaTypes
        },
        object : AcceptsResources {
            override val mediaTypes: Set<String> get() = session.runtime.inputSupport(
                session.target.model,
            ).resourceMediaTypes
        },
        blocked = {
            if (closed.value) EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed) else null
        },
    )
    private val closed = MutableStateFlow(false)
    private val observation = session.runtime.profile.coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
        session.machine.state.collect {
            log.d { "Codex lease state changed" }
            mutableState.value = it
        }
    }

    override suspend fun checkpoint(request: RequestId): String? = command { session.recovery.checkpoint(request) }
    override suspend fun inspect(checkpoint: String?) = command {
        session.runtime.gate()
        session.recovery.inspect(
            checkpoint,
        )
    }

    override suspend fun send(request: PromptRequest): TurnId = command { session.send(request) }
    override suspend fun cancel(turn: TurnId) = command { session.cancel(turn) }
    override suspend fun respond(decision: PermissionDecision) = command { session.respond(decision) }
    override suspend fun page(request: HistoryPageRequest): HistoryPage = command { session.history.page(request) }
    override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = channelFlow {
        ensureOpen()
        val updates = launch { session.history.watch(after).collect { send(it) } }
        val stopping = launch {
            closed.first { it }
            updates.cancel()
        }
        updates.join()
        stopping.cancel()
    }.flowOn(session.runtime.dispatchers.main)

    override suspend fun close() = withContext(session.runtime.dispatchers.main) {
        if (!closed.value) {
            log.i { "Codex lease released" }
            mutableState.value = ActiveSessionState.Closing()
            closed.value = true
            observation.cancel()
            session.release(this@CodexLease)
            mutableState.value = ActiveSessionState.Closed
        }
    }

    fun terminate(finalState: ActiveSessionState = session.machine.state.value) {
        log.i { "Codex lease invalidated" }
        mutableState.value = finalState
        closed.value = true
        observation.cancel()
    }

    private suspend fun <T> command(block: suspend () -> T): T = withContext(session.runtime.dispatchers.main) {
        ensureOpen()
        block()
    }

    private fun ensureOpen() {
        if (closed.value) fail(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed))
        session.runtime.ensureOpen()
    }
}
