package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionEffect
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionOutput
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SwitchesModels
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Minimal machine runtime over the pure spec, mirroring the core semantics used by the facade: state-scoped
 * effects are cancelled on `goto`, effect failures map through `onEffectFailure`, outputs are hot.
 */
internal class SpecMachine<S : MachineState, I : MachineIntent, E : MachineEffect, O : MachineOutput>(
    private val spec: MachineSpec<S, I, E, O>,
    private val scope: CoroutineScope,
    private val handler: EffectHandler<E, I>,
) : Machine<S, I, O> {
    private val log = Log.tag("SpecMachine")
    private val mutex = Mutex()
    private val current = MutableStateFlow(spec.initial)
    private val emitted = MutableSharedFlow<O>(extraBufferCapacity = 64)
    private var stateJob = SupervisorJob(scope.coroutineContext[Job])

    override val name: String = spec.name
    override val state: StateFlow<S> = current
    override val outputs: SharedFlow<O> = emitted

    override suspend fun send(intent: I): SendResult = mutex.withLock {
        if (!scope.isActive) return@withLock SendResult.NotRunning
        val resolution = spec.resolve(current.value, intent) ?: return@withLock SendResult.Ignored
        if (resolution.isStateChange) {
            stateJob.cancel()
            stateJob = SupervisorJob(scope.coroutineContext[Job])
        }
        current.value = resolution.to
        resolution.outputs.forEach { emitted.emit(it) }
        val job = stateJob
        resolution.effects.forEach { effect ->
            CoroutineScope(
                scope.coroutineContext + job,
            ).launch { run(effect, job) }
        }
        SendResult.Accepted
    }

    private suspend fun run(effect: E, job: Job) {
        try {
            handler.handle(effect, feedback(job))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "effect failed" }
            spec.onEffectFailure(effect, e)?.let { send(it) }
        }
    }

    private fun feedback(job: Job) = object : EffectScope<I> {
        override suspend fun send(intent: I): SendResult =
            if (job.isActive) this@SpecMachine.send(intent) else SendResult.Ignored
    }
}

/** Handle lifetime on a child job of [parent]. */
internal class TestHandleScope(override val id: String, parent: CoroutineScope) : SessionHandleScope {
    override val scope: CoroutineScope = CoroutineScope(
        parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]),
    )
    var isClosed = false

    override fun launch(
        spec: MachineSpec<ActiveSessionState, ActiveSessionIntent, ActiveSessionEffect, ActiveSessionOutput>,
        effects: ActiveSessionEffects,
    ): Machine<ActiveSessionState, ActiveSessionIntent, ActiveSessionOutput> = SpecMachine(spec, scope, effects)

    override fun close() {
        isClosed = true
        scope.cancel()
    }
}

internal val TestTarget = EngineTarget(TestEngine, EngineBindingId("b1"), ModelId("m1"))

internal fun permission(id: String, turn: TurnId) = PermissionRequest(
    PermissionRequestId(id),
    turn,
    "Run tool",
    listOf(
        PermissionOption(PermissionOptionId("allow"), "Allow"),
        PermissionOption(PermissionOptionId("deny"), "Deny"),
    ),
)

/** Native handle driven by the test: commands are recorded, state changes are set explicitly or on send. */
internal class FakeNativeSession(
    override val ref: SessionRef = sessionRef("native-1"),
    initial: ActiveSessionState = ActiveSessionState.Ready(),
) : ActiveSession {
    val native = MutableStateFlow(initial)
    val sent = mutableListOf<PromptRequest>()
    val cancelled = mutableListOf<TurnId>()
    val decisions = mutableListOf<PermissionDecision>()
    var sendFailure: Exception? = null
    var acceptOnSend = true

    /** Whether the published native turn carries the request id (adapters may not correlate). */
    var correlateOnSend = true
    var closes = 0
    var closeGate: CompletableDeferred<Unit>? = null
    val models = mutableListOf<ModelId>()

    override val route = ExecutionRoute(
        TestEngine,
        EngineBindingId("b1"),
        managedKey().info.id,
        AuthRevision.Known("r1"),
    )
    override val state: StateFlow<ActiveSessionState> = native

    override val features: EngineFeatures = FeatureTable(
        mapOf(
            SendsPrompts.id to available(
                object : SendsPrompts {
                    override suspend fun send(request: PromptRequest): TurnId {
                        sendFailure?.let { throw it }
                        sent += request
                        val id = TurnId("native-${sent.size}")
                        if (acceptOnSend) {
                            val correlated = request.id.takeIf { correlateOnSend }
                            native.value = ActiveSessionState.Running(Turn(id, correlated, TestTarget))
                        }
                        return id
                    }
                },
            ),
            CancelsTurns.id to available(
                object : CancelsTurns {
                    override suspend fun cancel(turn: TurnId) {
                        cancelled += turn
                    }
                },
            ),
            RequestsPermissions.id to available(
                object : RequestsPermissions {
                    override suspend fun respond(decision: PermissionDecision) {
                        decisions += decision
                    }
                },
            ),
            SwitchesModels.id to available(
                object : SwitchesModels {
                    override suspend fun switchTo(model: ModelId) {
                        models += model
                    }
                },
            ),
            ReconcilesSession.id to available(
                object : ReconcilesSession {
                    override suspend fun synchronize() = Unit
                },
            ),
        ),
    )

    val activeTurn: Turn get() = checkNotNull(native.value.activeTurn())

    fun finish(outcome: TurnOutcome = TurnOutcome.Completed) {
        native.value = ActiveSessionState.Ready(activeTurn.copy(outcome = outcome))
    }

    fun ask(request: String): PermissionRequest {
        val turn = activeTurn
        return permission(
            request,
            turn.id,
        ).also { native.value = ActiveSessionState.AwaitingUserAction(turn, listOf(it)) }
    }

    fun resolve(request: PermissionRequest) {
        native.value = ActiveSessionState.Running(activeTurn.copy(resolvedPermissions = setOf(request.id)))
    }

    override suspend fun close() {
        closes++
        closeGate?.await()
        native.value = ActiveSessionState.Closed
    }
}

/** Runtime handing out [FakeNativeSession]s for creation and attachment. */
internal class FakeRuntime(override val identity: RuntimeIdentity, private val supports: Boolean = true) :
    EngineRuntime {
    val sessions = mutableListOf<FakeNativeSession>()
    var closes = 0

    override val features: EngineFeatures = if (!supports) {
        NoEngineFeatures
    } else {
        FeatureTable(
            mapOf(
                CreatesSessions.id to available(
                    object : CreatesSessions {
                        override suspend fun create(request: CreateSessionRequest): ActiveSession =
                            FakeNativeSession(sessionRef("created-${sessions.size + 1}")).also { sessions += it }
                    },
                ),
                AttachesSessions.id to available(
                    object : AttachesSessions {
                        override suspend fun attach(ref: SessionRef, request: ResumeSessionRequest): ActiveSession =
                            FakeNativeSession(ref).also { sessions += it }
                    },
                ),
            ),
        )
    }

    override suspend fun close() {
        closes++
    }
}
