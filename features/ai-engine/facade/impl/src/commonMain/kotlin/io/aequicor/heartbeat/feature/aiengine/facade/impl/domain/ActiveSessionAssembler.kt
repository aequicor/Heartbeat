package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionEffect
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionMachineKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionOutput
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.activeSessionMachineSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** Lifetime of one handle, provided by the data layer: its scope and the machine launched in it. */
interface SessionHandleScope {
    /** Safe local handle id: `[A-Za-z0-9_-]`, no native paths or accounts. */
    val id: String

    /** Coroutines of the handle; cancelled when the handle scope closes. */
    val scope: CoroutineScope

    /** Starts the handle machine in this scope. */
    fun launch(
        spec: MachineSpec<ActiveSessionState, ActiveSessionIntent, ActiveSessionEffect, ActiveSessionOutput>,
        effects: ActiveSessionEffects,
    ): Machine<ActiveSessionState, ActiveSessionIntent, ActiveSessionOutput>

    /** Closes the handle scope after the handle reached Closed. */
    fun close()
}

/** Opens a facade handle around an attached native handle. */
fun interface ActiveSessionHost {
    /** A machine-backed handle for [native] on the checked [route]. */
    suspend fun open(native: ActiveSession, route: ExecutionRoute, model: ModelId): ActiveSession
}

/** Builds handles; native commands run in the profile-owned [commands] scope. */
class ActiveSessionAssembler(private val policy: SessionPolicy, private val commands: CoroutineScope) {
    /** Assembles and starts a handle in [handle]. */
    fun assemble(
        native: ActiveSession,
        route: ExecutionRoute,
        model: ModelId,
        handle: SessionHandleScope,
    ): ActiveSession {
        val effects = ActiveSessionEffects(native, commands)
        val spec = activeSessionMachineSpec(ActiveSessionMachineKey(handle.id), native.state.value.asInitial())
        val session = ManagedActiveSession(
            native.ref,
            route,
            model,
            SessionParts(
                native,
                handle.launch(spec, effects),
                effects,
                checkNotNull(handle.scope.coroutineContext[Job]),
            ),
            policy,
        )
        policy.registry.add(session)
        session.start(handle.scope, handle::close)
        return session
    }
}
