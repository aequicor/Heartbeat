package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Open handles of the profile. Commands are serialized per native session across handles, and a handle may
 * start a turn only while no other handle of the same session is executing one.
 */
class ActiveSessionRegistry : BindingUsage {
    private val log = Log.tag("ActiveSessions")
    private val handles = MutableStateFlow(emptyList<ActiveSession>())
    private val guard = Mutex()
    private val locks = mutableMapOf<SessionRef, Mutex>()

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

    /** Whether another handle of [ref] is executing a turn. */
    fun isBusy(ref: SessionRef, except: ActiveSession): Boolean =
        handles.value.any { it !== except && it.ref == ref && it.state.value.activeTurn() != null }

    /** Whether a handle executing through the runtime of [engine] and [source] still has a turn in flight. */
    fun hasActiveTurn(engine: EngineId, source: AuthSourceId): Boolean = handles.value.any {
        it.route.engine == engine && it.route.authSource == source && it.state.value.activeTurn() != null
    }

    /** Runs [block] exclusively for the native session [ref]. */
    suspend fun <T> exclusive(ref: SessionRef, block: suspend () -> T): T =
        guard.withLock { locks.getOrPut(ref) { Mutex() } }.withLock { block() }
}

/** Profile-wide rules shared by every handle: toggles, route rechecks, cross-handle serialization and ids. */
class SessionPolicy(
    private val routes: RouteResolver,
    private val enabled: EnabledEngines,
    val registry: ActiveSessionRegistry,
    private val context: FacadeContext,
) {
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
