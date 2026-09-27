package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionEffect
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionMachineKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SwitchesModels
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.activeSessionMachineSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

internal class PiSession(
    private val request: CreateSessionRequest,
    override val route: ExecutionRoute,
    private val environment: PiSessionEnvironment,
    private val validate: suspend () -> Unit,
) : ActiveSession,
    SendsPrompts,
    CancelsTurns,
    SwitchesModels,
    ReconcilesSession {
    private val log = Log.tag("PiSession")
    private val profile get() = environment.profile
    private val dispatchers get() = environment.dispatchers
    private val handle = environment.scopes.child(profile, "pi-" + UUID.randomUUID())
    private val journal = PiJournal()
    private val mutex = Mutex()
    private var connection: PiConnection? = null
    private var nativeRef: SessionRef? = null
    private var isTurnStarted = false
    private var isCommandPending = false
    private var pendingEffect: ActiveSessionEffect? = null
    private var isEffectStarted = false
    private var target = request.target
    private var turn: Turn? = null
    private var terminal: TurnOutcome = TurnOutcome.Completed
    private var acceptance: CompletableDeferred<TurnId>? = null
    private var cancellationAck: CompletableDeferred<Unit>? = null
    private val machine = environment.machines.launch(
        activeSessionMachineSpec(ActiveSessionMachineKey(UUID.randomUUID().toString()), ActiveSessionState.Ready()),
        handle,
        EffectHandler<ActiveSessionEffect, ActiveSessionIntent> { effect, _ ->
            // Accepted work belongs to the profile, never to this state-scoped effect or the caller.
            handoff(effect)
        },
    )
    init {
        profile.onClose {
            cancellationAck?.completeExceptionally(
                EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed)),
            )
            acceptance?.completeExceptionally(
                EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed)),
            )
        }
    }

    override val ref: SessionRef get() = requireNotNull(nativeRef)
    override val state = machine.state
    override val features: EngineFeatures = PiFeatures(
        listOf(
            SendsPrompts to this,
            CancelsTurns to this,
            SwitchesModels to this,
            ReconcilesSession to this,
            SessionHistory to journal,
        ),
    )

    suspend fun start(
        factory: suspend (suspend (JsonObject) -> Unit, suspend (EngineFailure) -> Unit) -> PiConnection,
    ) {
        var isStarted = false
        try {
            withContext(NonCancellable) { connection = factory(::event, ::failed) }
            currentCoroutineContext().ensureActive()
            rpc().command("set_model", modelFields(target.model))
            val snapshot = rpc().command("get_state")
            val nativeId = snapshot.string("sessionId")
                ?: piFailure(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
            nativeRef = SessionRef(route.engine, SessionSourceId("pi.profile"), nativeId)
            isStarted = true
        } finally {
            if (!isStarted) withContext(NonCancellable) { shutdown() }
        }
    }
    override suspend fun send(request: PromptRequest): TurnId = withContext(dispatchers.main) {
        val accepted = withContext(NonCancellable) {
            mutex.withLock {
                validate()
                ensureOpen()
                if (isCommandPending || state.value !is ActiveSessionState.Ready) {
                    piFailure(EngineFailure.Session(SessionFailureReason.Busy))
                }
                if (request.parts.any { it !is ContentPart.Text }) {
                    piFailure(EngineFailure.Request(RequestFailureReason.UnsupportedContent, request.id))
                }
                val next = Turn(TurnId(UUID.randomUUID().toString()), request.id, target)
                val result = CompletableDeferred<TurnId>()
                prepare(ActiveSessionEffect.Submit(request, next))
                acceptance = result
                turn = next
                isTurnStarted = false
                terminal = TurnOutcome.Completed
                if (machine.send(ActiveSessionIntent.Public.Submit(request, next)) != SendResult.Accepted) {
                    isCommandPending = false
                    pendingEffect = null
                    result.completeExceptionally(
                        EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed)),
                    )
                }
                handoff(ActiveSessionEffect.Submit(request, next))
                result
            }
        }
        accepted.await()
    }

    override suspend fun cancel(turn: TurnId): Unit = withContext(dispatchers.main) {
        val acknowledgement = CompletableDeferred<Unit>()
        withContext(NonCancellable) {
            mutex.withLock {
                validate()
                ensureOpen()
                if (isCommandPending) piFailure(EngineFailure.Session(SessionFailureReason.Busy))
                prepare(ActiveSessionEffect.Cancel(turn))
                cancellationAck = acknowledgement
                if (machine.send(ActiveSessionIntent.Public.Cancel(turn)) != SendResult.Accepted) {
                    isCommandPending = false
                    pendingEffect = null
                    piFailure(EngineFailure.Request(RequestFailureReason.Invalid))
                }
                handoff(ActiveSessionEffect.Cancel(turn))
            }
        }
        acknowledgement.await()
    }
    override suspend fun switchTo(model: ModelId): Unit = withContext(
        NonCancellable + dispatchers.main,
    ) {
        mutex.withLock {
            validate()
            ensureOpen()
            if (isCommandPending || state.value !is ActiveSessionState.Ready) {
                piFailure(EngineFailure.Session(SessionFailureReason.Busy))
            }
            try {
                rpc().command("set_model", modelFields(model))
            } catch (e: EngineException) {
                failed(e.failure)
                throw e
            }
            target = target.copy(model = model)
        }
    }

    override suspend fun synchronize(): Unit = withContext(dispatchers.main) {
        mutex.withLock {
            validate()
            ensureOpen()
            if (machine.send(ActiveSessionIntent.Public.Recheck) != SendResult.Accepted) {
                piFailure(EngineFailure.Request(RequestFailureReason.Invalid))
            }
            val snapshot = rpc().command("get_state")
            reconcile(snapshot)
        }
    }
    override suspend fun close() = mutex.withLock {
        withContext(dispatchers.main) {
            machine.send(ActiveSessionIntent.Public.Close)
            cancellationAck?.completeExceptionally(
                EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed)),
            )
            acceptance?.completeExceptionally(
                EngineException(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, turn?.request)),
            )
            machine.send(ActiveSessionIntent.Internal.Released)
            handle.close()
        }
    }

    suspend fun shutdown() = withContext(NonCancellable + dispatchers.main) {
        val active = turn
        if (active != null && isTurnStarted) {
            finish(TurnOutcome.Unknown)
        } else if (active != null) {
            acceptance?.completeExceptionally(
                EngineException(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, active.request)),
            )
            turn = null
        }
        machine.send(ActiveSessionIntent.Public.Close)
        machine.send(ActiveSessionIntent.Internal.Released)
        connection?.close()
        connection = null
        acceptance?.completeExceptionally(
            EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed)),
        )
        handle.close()
    }

    private fun prepare(effect: ActiveSessionEffect) {
        pendingEffect = effect
        isEffectStarted = false
        isCommandPending = true
    }

    private fun handoff(effect: ActiveSessionEffect) {
        if (pendingEffect == effect && !isEffectStarted) {
            isEffectStarted = true
            profile.coroutineScope.launch { execute(effect) }
        }
    }
    private suspend fun execute(effect: ActiveSessionEffect) {
        val accepted = acceptance
        try {
            when (effect) {
                is ActiveSessionEffect.Submit -> submit(effect)

                is ActiveSessionEffect.Cancel -> {
                    if (turn?.id == effect.turn) rpc().command("abort")
                    cancellationAck?.complete(Unit)
                }

                is ActiveSessionEffect.Recheck -> Unit

                // synchronize() owns the response barrier.
                is ActiveSessionEffect.Release -> Unit

                // close() owns the release barrier.
                is ActiveSessionEffect.Decide ->
                    piFailure(EngineFailure.Engine(EngineFailureReason.UnsupportedCapability))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "Pi native command failed" }
            commandFailed(effect, accepted, e.failure)
        } catch (e: Exception) {
            log.w(EngineException(EngineFailure.Unknown())) { "Pi command failed: ${e::class.simpleName.orEmpty()}" }
            commandFailed(effect, accepted, EngineFailure.Unknown())
        } finally {
            if (effect is ActiveSessionEffect.Submit || effect is ActiveSessionEffect.Cancel) isCommandPending = false
        }
    }

    private suspend fun commandFailed(
        effect: ActiveSessionEffect,
        accepted: CompletableDeferred<TurnId>?,
        original: EngineFailure,
    ) {
        val failure = if (effect is ActiveSessionEffect.Submit && original !is EngineFailure.Request) {
            EngineFailure.Request(RequestFailureReason.OutcomeUnknown, effect.request.id)
        } else {
            original
        }
        log.w(EngineException(failure)) { "Pi session command failed" }
        accepted?.completeExceptionally(EngineException(failure))
        if (effect is ActiveSessionEffect.Cancel) cancellationAck?.completeExceptionally(EngineException(failure))
        val affected = when (effect) {
            is ActiveSessionEffect.Submit -> effect.turn.id
            is ActiveSessionEffect.Cancel -> effect.turn
            is ActiveSessionEffect.Recheck, ActiveSessionEffect.Release, is ActiveSessionEffect.Decide -> turn?.id
        }
        if (turn?.id == affected) failed(failure)
    }
    private suspend fun submit(effect: ActiveSessionEffect.Submit) {
        validate()
        val accepted = acceptance
        val message = effect.request.parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }
        val response = rpc().command("prompt", JsonObject(mapOf("message" to JsonPrimitive(message))))
        if (turn?.id == effect.turn.id) machine.send(ActiveSessionIntent.Internal.Accepted(effect.turn.id))
        started(effect.turn)
        accepted?.complete(effect.turn.id)
        if (response.string("disposition") == "handled" && turn?.id == effect.turn.id) finish(TurnOutcome.Unknown)
    }

    private suspend fun event(record: JsonObject) = withContext(dispatchers.main) {
        journal.record(record, turn?.id)
        when (record.string("type")) {
            "agent_start" -> turn?.let {
                if (state.value != ActiveSessionState.Closed) machine.send(ActiveSessionIntent.Internal.Accepted(it.id))
                started(it)
                acceptance?.complete(it.id)
            }

            "message_end" -> {
                val message = record["message"] as? JsonObject
                if (message?.string("role") == "assistant") {
                    terminal = when (message.string("stopReason")) {
                        "aborted" -> TurnOutcome.Cancelled
                        "error" -> TurnOutcome.Failed(EngineFailure.Unknown())
                        else -> TurnOutcome.Completed
                    }
                }
            }

            "agent_settled" -> finish(terminal)
        }
    }

    private fun started(accepted: Turn) {
        if (!isTurnStarted) {
            journal.started(accepted)
            isTurnStarted = true
        }
    }
    private suspend fun finish(outcome: TurnOutcome) {
        val completed = turn ?: return
        started(completed)
        acceptance?.complete(completed.id)
        if (state.value != ActiveSessionState.Closed) {
            machine.send(
                ActiveSessionIntent.Internal.Finished(completed.id, outcome),
            )
        }
        journal.finished(completed.id, outcome)
        turn = null
    }

    private suspend fun failed(failure: EngineFailure) = withContext(dispatchers.main) {
        if (state.value != ActiveSessionState.Closed) {
            machine.send(ActiveSessionIntent.Internal.Failed(turn?.id, failure))
        }
    }

    private suspend fun reconcile(snapshot: JsonObject) {
        val model = snapshot["model"] as? JsonObject
            ?: piFailure(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
        val provider = model.string("provider")
        val id = model.string("id")
        if (provider != request.target.model.value.substringBefore("/") || id.isNullOrBlank()) {
            piFailure(EngineFailure.Access(AccessFailureReason.ModelAccessDenied))
        }
        target = target.copy(model = ModelId("$provider/$id"))
        val isBusy = snapshot["isStreaming"]?.jsonPrimitive?.booleanOrNull == true ||
            snapshot["isCompacting"]?.jsonPrimitive?.booleanOrNull == true
        val remembered = turn
        if (isBusy && remembered == null) piFailure(EngineFailure.Session(SessionFailureReason.Changed))
        val completed = if (!isBusy && remembered != null) {
            ActiveSessionIntent.Internal.Finished(remembered.id, TurnOutcome.Unknown)
        } else {
            null
        }
        val intent = ActiveSessionIntent.Internal.Synchronized(if (isBusy) remembered else null, completed = completed)
        if (machine.send(intent) != SendResult.Accepted) return
        if (completed != null) {
            journal.finished(completed.turn, completed.outcome)
            turn = null
        }
    }

    private fun ensureOpen() {
        if (state.value is ActiveSessionState.Closing || state.value == ActiveSessionState.Closed) {
            piFailure(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed))
        }
    }

    private fun modelFields(model: ModelId): JsonObject {
        val provider = model.value.substringBefore("/")
        if (provider != request.target.model.value.substringBefore("/") || "/" !in model.value) {
            piFailure(EngineFailure.Access(AccessFailureReason.ModelAccessDenied))
        }
        return JsonObject(
            mapOf("provider" to JsonPrimitive(provider), "modelId" to JsonPrimitive(model.value.substringAfter("/"))),
        )
    }

    private fun rpc(): PiConnection = connection
        ?: piFailure(EngineFailure.Engine(EngineFailureReason.Unavailable))
}
