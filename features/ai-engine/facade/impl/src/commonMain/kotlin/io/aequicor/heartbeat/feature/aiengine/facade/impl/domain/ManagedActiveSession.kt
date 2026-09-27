package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionOutput
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SwitchesModels
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Parts of one handle: the adapter's native handle, the machine that owns its lifecycle, its effects and the
 * [lifetime] of the handle scope, which also ends when the profile closes.
 */
data class SessionParts(
    val native: ActiveSession,
    val machine: Machine<ActiveSessionState, ActiveSessionIntent, ActiveSessionOutput>,
    val effects: ActiveSessionEffects,
    val lifetime: Job,
)

/**
 * Facade handle following ActiveSessionMachineSpec. Commands go through the machine; native observations reach
 * it through a bridge running in the handle scope, independently of state-scoped effects. [send] completes only
 * after native acceptance, and every ignored command is reported as a domain failure.
 */
class ManagedActiveSession(
    override val ref: SessionRef,
    override val route: ExecutionRoute,
    model: ModelId,
    private val parts: SessionParts,
    private val policy: SessionPolicy,
) : ActiveSession {
    private val log = Log.tag("ActiveSession")
    private val machine = parts.machine
    private val currentModel = MutableStateFlow(model)

    override val state: StateFlow<ActiveSessionState> get() = machine.state

    override val features: EngineFeatures = GatedFeatures(
        FeatureTable(
            mapOf(
                SendsPrompts.id to parts.native.features.wrap(SendsPrompts) { Sender() },
                CancelsTurns.id to parts.native.features.wrap(CancelsTurns) { Canceller() },
                RequestsPermissions.id to parts.native.features.wrap(RequestsPermissions) { Permissions() },
                ReconcilesSession.id to available(Reconciler()),
                SwitchesModels.id to parts.native.features.wrap(SwitchesModels) { ModelSwitcher(it) },
            ),
            parts.native.features,
        ),
    ) { policy.blocker(route, machine.state.value) }

    /** Starts the native bridge and the closing watcher in the handle [scope]. */
    fun start(scope: CoroutineScope, onClosed: () -> Unit) {
        scope.launch { bridge() }
        scope.launch {
            machine.state.first { it == ActiveSessionState.Closed }
            policy.registry.remove(this@ManagedActiveSession)
            onClosed()
        }
    }

    override suspend fun close() {
        log.i { "close handle engine=${ref.engine.value}" }
        if (machine.state.value == ActiveSessionState.Closed) return
        if (machine.send(ActiveSessionIntent.Public.Close) == SendResult.NotRunning) {
            log.w { "close after profile shutdown; runtime already released" }
            return
        }
        val settled = untilStopped {
            machine.state.first {
                it == ActiveSessionState.Closed || (it is ActiveSessionState.Closing && it.failure != null)
            }
        }
        (settled as? ActiveSessionState.Closing)?.failure?.let { fail(it) }
    }

    private suspend fun bridge() {
        var lastNative: ActiveSessionState? = null
        // The machine state only triggers a pass; each pass reads the latest state, not the triggering snapshot.
        combine(parts.native.state, machine.state) { native, _ -> native }.collect { native ->
            val isNativeChanged = native != lastNative
            lastNative = native
            val current = machine.state.value
            val local = parts.effects.correlation.localize(native, current)
            reconcile(current, local).forEach { machine.send(it) }
            if (isNativeChanged && machine.state.value is ActiveSessionState.Unavailable && local.isReachable()) {
                log.i { "native side reachable again, reconciling" }
                machine.send(ActiveSessionIntent.Public.Recheck)
            }
        }
    }

    private suspend fun submit(request: PromptRequest, turn: Turn): TurnId = coroutineScope {
        // Outputs are hot and the runtime may emit them while processing Submit: subscribe synchronously first.
        val answer = async(start = CoroutineStart.UNDISPATCHED) {
            machine.outputs.first {
                (it is ActiveSessionOutput.Accepted && it.turn.id == turn.id) ||
                    (it is ActiveSessionOutput.SubmissionFailed && it.request == request.id)
            }
        }
        val result = machine.send(ActiveSessionIntent.Public.Submit(request, turn))
        if (result != SendResult.Accepted) {
            answer.cancel()
            fail(result.failure())
        }
        when (val outcome = untilStopped { answer.await() }) {
            is ActiveSessionOutput.Accepted -> turn.id
            is ActiveSessionOutput.SubmissionFailed -> fail(outcome.failure)
            is ActiveSessionOutput.Finished -> error("filtered out")
        }
    }

    /** Awaits [block], failing with ProfileClosed if the handle scope ends first (its machine stopped). */
    private suspend fun <T> untilStopped(block: suspend () -> T): T = coroutineScope {
        val result = async(start = CoroutineStart.UNDISPATCHED) { block() }
        select {
            result.onAwait { it }
            parts.lifetime.onJoin {
                result.cancel()
                fail(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
            }
        }
    }

    private suspend fun command(intent: ActiveSessionIntent.Public) {
        val result = machine.send(intent)
        if (result != SendResult.Accepted) fail(if (result == SendResult.Ignored) InvalidRequest else result.failure())
    }

    private inner class Sender : SendsPrompts {
        override suspend fun send(request: PromptRequest): TurnId {
            log.i { "send request=${request.id.value} parts=${request.parts.size}" }
            val model = currentModel.value
            policy.beforeTurn(route, model)
            return policy.registry.exclusive(ref) {
                if (policy.registry.isBusy(ref, this@ManagedActiveSession)) fail(Busy)
                when (val current = machine.state.value) {
                    is ActiveSessionState.Ready -> Unit

                    is ActiveSessionState.Unavailable -> fail(current.failure)

                    is ActiveSessionState.Closing, ActiveSessionState.Closed ->
                        fail(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed))

                    else -> fail(Busy)
                }
                val turn = Turn(policy.newTurnId(), request.id, EngineTarget(route.engine, route.binding, model))
                submit(request, turn)
            }
        }
    }

    private inner class Canceller : CancelsTurns {
        override suspend fun cancel(turn: TurnId) {
            log.i { "cancel turn=${turn.value}" }
            command(ActiveSessionIntent.Public.Cancel(turn))
        }
    }

    private inner class Permissions : RequestsPermissions {
        override suspend fun respond(decision: PermissionDecision) {
            log.i { "respond turn=${decision.turn.value} request=${decision.request.value}" }
            command(ActiveSessionIntent.Public.Decide(decision))
        }
    }

    private inner class Reconciler : ReconcilesSession {
        override suspend fun synchronize() {
            log.i { "synchronize" }
            if (machine.state.value !is ActiveSessionState.Unavailable) return
            coroutineScope {
                val done = async(start = CoroutineStart.UNDISPATCHED) { parts.effects.rechecks.first() }
                val result = machine.send(ActiveSessionIntent.Public.Recheck)
                if (result != SendResult.Accepted) {
                    done.cancel()
                    fail(result.failure())
                }
                untilStopped { done.await() }
            }
            (machine.state.value as? ActiveSessionState.Unavailable)?.let { fail(it.failure) }
        }
    }

    private inner class ModelSwitcher(private val native: SwitchesModels) : SwitchesModels {
        override suspend fun switchTo(model: ModelId) {
            log.i { "switch model engine=${route.engine.value}" }
            policy.beforeTurn(route, model)
            policy.registry.exclusive(ref) {
                if (machine.state.value !is ActiveSessionState.Ready) fail(Busy)
                if (policy.registry.isBusy(ref, this@ManagedActiveSession)) fail(Busy)
                adapterCall(log, "switchModel") { native.switchTo(model) }
                currentModel.value = model
            }
        }
    }
}

private val Busy = EngineFailure.Session(SessionFailureReason.Busy)

private fun SendResult.failure(): EngineFailure = when (this) {
    SendResult.NotRunning -> EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed)
    SendResult.Ignored, SendResult.Accepted -> Busy
}
