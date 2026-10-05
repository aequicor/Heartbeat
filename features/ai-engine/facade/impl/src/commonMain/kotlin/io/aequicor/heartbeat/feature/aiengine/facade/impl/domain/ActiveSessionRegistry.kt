package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionObservationSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Open handles of the profile. Commands are serialized per native session across handles, and a handle may
 * start a turn only while no other handle of the same session is executing one.
 */
class ActiveSessionRegistry : BindingUsage {
    private val log = Log.tag("ActiveSessions")
    private val handles = MutableStateFlow(emptyList<ActiveSession>())
    private val guard = Mutex()
    private val locks = mutableMapOf<SessionRef, SessionLock>()

    /** Open handles and turns in flight per engine; follows every handle's state, closed handles excluded. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val summary: Flow<Map<EngineId, SessionCounts>> = handles.flatMapLatest { open ->
        if (open.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(open.map { handle -> handle.state.map { state -> handle.route.engine to state } }) { states ->
                states.filter { (_, state) -> state != ActiveSessionState.Closed }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, live) -> SessionCounts(live.size, live.count { it.activeTurn() != null }) }
            }
        }
    }.distinctUntilChanged()

    /** Number of per-session locks currently held or awaited; exposed for leak tests. */
    internal suspend fun lockCount(): Int = guard.withLock { locks.size }

    /** Registers an opened handle. */
    fun add(handle: ActiveSession) {
        log.i { "handle opened engine=${handle.ref.engine.value} binding=${handle.route.binding.value}" }
        handles.update { it + handle }
    }

    /** Forgets a closed handle. */
    fun remove(handle: ActiveSession) {
        log.i { "handle closed engine=${handle.ref.engine.value}" }
        handles.update { current -> current.filterNot { it === handle } }
    }

    override fun isInUse(binding: EngineBindingId): Boolean =
        handles.value.any { it.route.binding == binding && it.state.value != ActiveSessionState.Closed }

    /** Borrows only history from an open handle of the exact session, without acquiring its lifecycle. */
    fun history(ref: SessionRef): FeatureAccess<SessionHistory>? = handles.value.asSequence()
        .filter {
            it.ref == ref && it.state.value != ActiveSessionState.Closed &&
                it.state.value !is ActiveSessionState.Closing
        }
        .map { handle ->
            when (val access = handle.features.resolve(SessionHistory)) {
                is FeatureAccess.Available -> FeatureAccess.Available(BorrowedSessionHistory(handle, access.feature))
                is FeatureAccess.Unavailable -> access
                FeatureAccess.Unsupported -> access
            }
        }
        .firstOrNull { it != FeatureAccess.Unsupported }

    /** Observes execution without giving a reader ownership of a handle or permission to drive it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(ref: SessionRef): Flow<SessionObservationSnapshot?> = handles
        .map { open -> open.filter { it.ref == ref } }
        .distinctUntilChanged()
        .flatMapLatest { matching ->
            if (matching.isEmpty()) {
                flowOf(null)
            } else {
                combine(matching.map { handle -> handle.state.map { handle to it } }) { it.toList() }
                    .map { states ->
                        states.firstOrNull { it.second.activeTurn() != null }
                            ?: states.firstOrNull {
                                it.second != ActiveSessionState.Closed && it.second !is ActiveSessionState.Closing
                            }
                    }
                    .distinctUntilChanged()
                    .flatMapLatest { selected ->
                        if (selected == null) {
                            flowOf(null)
                        } else {
                            val (handle, state) = selected
                            val usage = handle.features.resolve(SessionContextUsage)
                            val contexts = (usage as? FeatureAccess.Available)?.feature?.state ?: flowOf(null)
                            contexts.map { SessionObservationSnapshot(state, handle.route, it) }
                        }
                    }
            }
        }.distinctUntilChanged()

    /** Whether another handle of [ref] is executing a turn. */
    fun isBusy(ref: SessionRef, except: ActiveSession): Boolean =
        handles.value.any { it !== except && it.ref == ref && it.state.value.activeTurn() != null }

    /** Whether a handle executing through the runtime of [engine] and [source] still has a turn in flight. */
    fun hasActiveTurn(engine: EngineId, source: AuthSourceId): Boolean = handles.value.any {
        it.route.engine == engine && it.route.authSource == source && it.state.value.activeTurn() != null
    }

    /**
     * Closes every open handle executing through the runtime of [engine] and [source], before that runtime is
     * retired: an idle handle must not outlive its runtime. A failed close is logged and does not stop the rest.
     */
    suspend fun closeHandles(engine: EngineId, source: AuthSourceId) {
        val open = handles.value.filter {
            it.route.engine == engine && it.route.authSource == source && it.state.value != ActiveSessionState.Closed
        }
        log.i { "close handles of retired runtime engine=${engine.value} count=${open.size}" }
        open.forEach { handle ->
            try {
                handle.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "handle close failed engine=${engine.value}" }
            }
        }
    }

    /**
     * Runs [block] exclusively for the native session [ref]. The per-session lock is reference-counted under
     * [guard] and dropped once no caller holds or awaits it, so the map does not grow for the profile lifetime.
     */
    suspend fun <T> exclusive(ref: SessionRef, block: suspend () -> T): T {
        val lock = guard.withLock { locks.getOrPut(ref) { SessionLock() }.also { it.users++ } }
        try {
            return lock.mutex.withLock { block() }
        } finally {
            withContext(NonCancellable) {
                guard.withLock {
                    lock.users--
                    if (lock.users == 0) locks.remove(ref)
                }
            }
        }
    }

    private class SessionLock {
        val mutex = Mutex()
        var users = 0
    }
}

/** Handles of one engine that are still open and how many of them execute a turn. */
data class SessionCounts(val open: Int, val activeTurns: Int)

/** Profile-wide rules shared by every handle: toggles, route rechecks, cross-handle serialization and ids. */
class SessionPolicy(
    private val routes: RouteResolver,
    private val enabled: EnabledEngines,
    val registry: ActiveSessionRegistry,
    private val context: FacadeContext,
    private val tools: ProfileAgentTools = NoAgentTools,
) {
    /** Registers the canonical identity before the native adapter can call hosted tools. */
    suspend fun bindTurn(session: SessionRef, request: RequestId, turn: Turn) {
        tools.bindTurn(session, request, turn.id, turn.target)
    }

    /** Rechecks toggles, binding, ownership and source revision before a turn or model change. */
    suspend fun beforeTurn(route: ExecutionRoute, model: ModelId) {
        routes.recheck(route, model)
    }

    /** Why capabilities of a handle are blocked right now, or null. */
    fun blocker(route: ExecutionRoute, state: ActiveSessionState): EngineFailure? = when {
        state is ActiveSessionState.Closing || state == ActiveSessionState.Closed ->
            EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed)

        route.engine !in enabled.state.value -> EngineUnavailable

        else -> null
    }

    /** A fresh local turn id. */
    fun newTurnId(): TurnId = TurnId(context.token("turn_"))
}
