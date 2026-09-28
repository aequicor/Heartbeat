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
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.UUID

internal typealias PiConnector =
    suspend (event: suspend (JsonObject) -> Unit, failed: suspend (EngineFailure) -> Unit) -> PiConnection

/**
 * One native Pi session over a dedicated process. Turn completion follows Pi's `agent_settled` event
 * (`docs/rpc.md`: `agent_end` may be followed by retries). No extension commands are registered, so every
 * accepted prompt produces an agent run. The process is released once the handle is closed and no turn runs;
 * [released] lets the owning runtime forget this session.
 */
internal class PiSession(
    private val request: CreateSessionRequest,
    override val route: ExecutionRoute,
    private val environment: PiSessionEnvironment,
    private val validate: suspend () -> Unit,
    private val released: (PiSession) -> Unit = {},
) : ActiveSession,
    SendsPrompts,
    CancelsTurns,
    SwitchesModels,
    ReconcilesSession,
    RequestsPermissions {
    private val log = Log.tag("PiSession")
    private val profile get() = environment.profile
    private val dispatchers get() = environment.dispatchers
    private val handle = environment.scopes.child(profile, "pi-" + UUID.randomUUID())
    private val journal = PiJournal()
    private val mutex = Mutex()
    private var connection: PiConnection? = null
    private var connector: PiConnector? = null
    private var nativeRef: SessionRef? = null

    // Native transcript path, used only to reattach a restarted process; never logged.
    private var sessionFile: String? = null
    private var isHandleClosed = false
    private var isReleased = false

    // Callbacks of a replaced or failed process are ignored once a newer connection generation exists.
    private var generation = 0
    private var isTurnStarted = false
    private var isCommandPending = false
    private var pendingEffect: ActiveSessionEffect? = null
    private var isEffectStarted = false
    private var target = request.target
    private var turn: Turn? = null
    private var terminal: TurnOutcome = TurnOutcome.Completed
    private var acceptance: CompletableDeferred<TurnId>? = null
    private var cancellationAck: CompletableDeferred<Unit>? = null

    // Pending tool approvals keyed by the Pi extension UI request id; confined to dispatchers.main.
    private val permissions = mutableMapOf<PermissionRequestId, PermissionRequest>()
    private val decisions = mutableSetOf<PermissionRequestId>()
    private val machine = environment.machines.launch(
        activeSessionMachineSpec(ActiveSessionMachineKey(UUID.randomUUID().toString()), ActiveSessionState.Ready()),
        handle,
        EffectHandler<ActiveSessionEffect, ActiveSessionIntent> { effect, _ ->
            // Accepted work belongs to the profile, never to this state-scoped effect or the caller.
            if (effect is ActiveSessionEffect.Decide) decide(effect.decision) else handoff(effect)
        },
    )
    private val profileClose = profile.onClose {
        cancellationAck?.completeExceptionally(
            EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed)),
        )
        acceptance?.completeExceptionally(
            EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed)),
        )
    }

    override val ref: SessionRef get() = requireNotNull(nativeRef)
    override val state = machine.state
    override val features: EngineFeatures = PiFeatures(
        listOf(
            SendsPrompts to this,
            CancelsTurns to this,
            SwitchesModels to this,
            ReconcilesSession to this,
            RequestsPermissions to this,
            SessionHistory to journal,
        ),
    )

    suspend fun start(factory: PiConnector): Unit = withContext(dispatchers.main) {
        var isStarted = false
        try {
            connector = factory
            withContext(NonCancellable) { connection = open(factory) }
            currentCoroutineContext().ensureActive()
            rpc().command("set_model", modelFields(target.model))
            val snapshot = rpc().command("get_state")
            val nativeId = snapshot.string("sessionId")
                ?: piFailure(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
            nativeRef = SessionRef(route.engine, SessionSourceId("pi.profile"), nativeId)
            sessionFile = snapshot.string("sessionFile")
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
                validatePromptRequest(request)
                val next = Turn(TurnId(UUID.randomUUID().toString()), request.id, target)
                val result = CompletableDeferred<TurnId>()
                val previous = Triple(turn, acceptance, isTurnStarted)
                prepare(ActiveSessionEffect.Submit(request, next))
                acceptance = result
                turn = next
                isTurnStarted = false
                terminal = TurnOutcome.Completed
                if (machine.send(ActiveSessionIntent.Public.Submit(request, next)) != SendResult.Accepted) {
                    isCommandPending = false
                    pendingEffect = null
                    turn = previous.first
                    acceptance = previous.second
                    isTurnStarted = previous.third
                    piFailure(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed))
                }
                handoff(ActiveSessionEffect.Submit(request, next))
                result
            }
        }
        accepted.await()
    }

    private fun validatePromptRequest(request: PromptRequest) {
        if (request.parts.any { it !is ContentPart.Text }) {
            piFailure(EngineFailure.Request(RequestFailureReason.UnsupportedContent, request.id))
        }
        if (request.reasoningEffort != null) {
            piFailure(EngineFailure.Request(RequestFailureReason.Invalid, request.id))
        }
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
                // A rejected model leaves the session usable; only transport or process loss needs recovery.
                if (e.failure !is EngineFailure.Request && e.failure !is EngineFailure.Access) failed(e.failure)
                throw e
            }
            target = target.copy(model = model)
        }
    }

    /** Answers a pending tool approval; the Pi extension blocks the tool unless the allow option is chosen. */
    override suspend fun respond(decision: PermissionDecision): Unit = withContext(NonCancellable + dispatchers.main) {
        mutex.withLock {
            validate()
            ensureOpen()
            if (machine.send(ActiveSessionIntent.Public.Decide(decision)) != SendResult.Accepted) {
                piFailure(EngineFailure.Request(RequestFailureReason.Invalid))
            }
            decide(decision)
        }
    }

    override suspend fun synchronize(): Unit = withContext(dispatchers.main) {
        mutex.withLock {
            validate()
            ensureOpen()
            if (machine.send(ActiveSessionIntent.Public.Recheck) != SendResult.Accepted) {
                piFailure(EngineFailure.Request(RequestFailureReason.Invalid))
            }
            val snapshot = withContext(NonCancellable) { connected() }.command("get_state")
            reconcile(snapshot)
        }
    }

    /** Detaches the handle; the process stays until an accepted native turn settles, then it is released. */
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
            isHandleClosed = true
            // Nobody can answer approvals of a detached handle; declining them lets the native turn settle.
            dismissApprovals()
            if (turn == null || connection?.isOpen != true) release()
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
        acceptance?.completeExceptionally(
            EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed)),
        )
        handle.close()
        isHandleClosed = true
        release()
    }

    private fun release() {
        if (isReleased) return
        isReleased = true
        connection?.let {
            log.i { "Releasing Pi process of a closed session" }
            it.close()
        }
        connection = null
        profileClose.dispose()
        released(this)
    }

    private suspend fun open(factory: PiConnector): PiConnection {
        val current = ++generation
        return factory(
            { record -> withContext(dispatchers.main) { if (current == generation) event(record) } },
            { failure -> withContext(dispatchers.main) { if (current == generation) failed(failure) } },
        )
    }

    /** Returns the live connection, restarting Pi on the same native transcript after process loss. */
    private suspend fun connected(): PiConnection {
        connection?.takeIf { it.isOpen }?.let { return it }
        connection?.close()
        connection = null
        // Approvals belonged to the lost process; its extension can no longer receive an answer.
        permissions.clear()
        decisions.clear()
        val file = sessionFile ?: piFailure(EngineFailure.Session(SessionFailureReason.NotResumable))
        val factory = connector ?: piFailure(EngineFailure.Engine(EngineFailureReason.Unavailable))
        log.i { "Restarting Pi process for session recovery" }
        val fresh = open(factory)
        try {
            val switched = fresh.command("switch_session", JsonObject(mapOf("sessionPath" to JsonPrimitive(file))))
            if ((switched["cancelled"] as? JsonPrimitive)?.booleanOrNull == true) {
                piFailure(EngineFailure.Session(SessionFailureReason.Changed))
            }
        } catch (e: EngineException) {
            // Never keep a process that sits on a different transcript than this handle.
            log.w(e) { "Pi session recovery could not reattach the transcript" }
            fresh.close()
            throw e
        }
        connection = fresh
        return fresh
    }

    private fun prepare(effect: ActiveSessionEffect) {
        pendingEffect = effect
        isEffectStarted = false
        isCommandPending = true
    }

    private fun handoff(effect: ActiveSessionEffect) {
        if (pendingEffect == effect && !isEffectStarted) {
            isEffectStarted = true
            profile.coroutineScope.launch(dispatchers.main) { execute(effect) }
        }
    }
    private suspend fun execute(effect: ActiveSessionEffect) {
        val accepted = acceptance
        try {
            when (effect) {
                is ActiveSessionEffect.Submit -> submit(effect)

                is ActiveSessionEffect.Cancel -> {
                    dismissApprovals()
                    if (turn?.id == effect.turn) rpc().command("abort")
                    cancellationAck?.complete(Unit)
                }

                is ActiveSessionEffect.Recheck -> Unit

                // synchronize() owns the response barrier.
                is ActiveSessionEffect.Release -> Unit

                // close() owns the release barrier; decisions bypass handoff through decide().
                is ActiveSessionEffect.Decide -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PromptNotSentException) {
            log.w(e) { "Pi prompt was rejected before delivery" }
            commandFailed(effect, accepted, e.failure, isDelivered = false)
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
        isDelivered: Boolean = true,
    ) {
        val failure = if (effect is ActiveSessionEffect.Submit && isDelivered && original !is EngineFailure.Request) {
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
        if (effect is ActiveSessionEffect.Submit && (!isDelivered || original is EngineFailure.Request)) {
            // Pi never accepted this prompt, so no agent_settled will release the turn.
            if (turn?.id == effect.turn.id) {
                turn = null
                isTurnStarted = false
            }
            if (isHandleClosed) release()
        }
    }
    private suspend fun submit(effect: ActiveSessionEffect.Submit) {
        try {
            validate()
        } catch (e: EngineException) {
            throw PromptNotSentException(e.failure, e)
        }
        val accepted = acceptance
        val message = effect.request.parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }
        rpc().command("prompt", JsonObject(mapOf("message" to JsonPrimitive(message))))
        if (turn?.id == effect.turn.id) machine.send(ActiveSessionIntent.Internal.Accepted(effect.turn.id))
        started(effect.turn)
        accepted?.complete(effect.turn.id)
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

            "extension_ui_request" -> uiRequest(record)
        }
    }

    private suspend fun uiRequest(record: JsonObject) {
        val id = record.string("id") ?: return
        // Fire-and-forget UI (notify, status, widgets) needs no answer.
        if (record.string("method") !in DIALOG_METHODS) return
        val active = turn
        val request = if (record.string("method") == "confirm" && record.string("title") == APPROVAL_TITLE) {
            active?.let { approvalRequest(id, it.id, record.string("message")) }
        } else {
            null
        }
        if (request == null || isHandleClosed) {
            dismiss(id)
            return
        }
        permissions[request.id] = request
        if (machine.send(ActiveSessionIntent.Internal.PermissionNeeded(request)) == SendResult.Accepted) {
            log.i { "Pi tool call awaits user approval" }
        } else {
            permissions.remove(request.id)
            dismiss(id)
        }
    }

    private fun approvalRequest(id: String, turn: TurnId, message: String?): PermissionRequest? {
        val fields = try {
            message?.let { Json.parseToJsonElement(it) as? JsonObject }
        } catch (e: SerializationException) {
            // Parser messages quote the input, which contains the command; the request stays blocked.
            log.w(EngineException(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))) {
                "Malformed Pi approval request: ${e::class.simpleName.orEmpty()}"
            }
            null
        } ?: return null
        val tool = fields.string("toolName")?.takeIf { it.isNotBlank() } ?: return null
        val raw = fields.string("target").orEmpty()
        if (raw.length > APPROVAL_TARGET_LIMIT) {
            // Approving a partially shown command is not consent; the tool call is blocked instead.
            log.w { "Pi tool call is too long to show for approval; blocking it" }
            return null
        }
        val target = visible(raw)
        return PermissionRequest(
            PermissionRequestId(id),
            turn,
            if (target.isBlank()) tool else "$tool: $target",
            listOf(PermissionOption(AllowOption, "Разрешить"), PermissionOption(DenyOption, "Запретить")),
        )
    }

    private fun decide(decision: PermissionDecision) {
        if (decisions.add(decision.request)) {
            profile.coroutineScope.launch(dispatchers.main) { answer(decision) }
        }
    }

    private suspend fun answer(decision: PermissionDecision) {
        permissions.remove(decision.request) ?: return
        try {
            val isAllowed = decision.option == AllowOption
            rpc().send(
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("extension_ui_response"),
                        "id" to JsonPrimitive(decision.request.value),
                        "confirmed" to JsonPrimitive(isAllowed),
                    ),
                ),
            )
            // Pi does not acknowledge dialog answers; handing the answer to the process resolves the request.
            machine.send(ActiveSessionIntent.Internal.PermissionResolved(decision.turn, decision.request))
            log.i { if (isAllowed) "Pi tool call allowed by user" else "Pi tool call denied by user" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "Pi approval answer was not delivered" }
            decisions.remove(decision.request)
            if (turn?.id == decision.turn) failed(e.failure)
        }
    }

    private suspend fun dismissApprovals() {
        val pending = permissions.keys.toList()
        permissions.clear()
        pending.forEach { dismiss(it.value) }
    }

    /** Declines a dialog nobody can answer; for an approval this blocks the tool call. */
    private suspend fun dismiss(id: String) {
        try {
            connection?.takeIf { it.isOpen }?.send(
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("extension_ui_response"),
                        "id" to JsonPrimitive(id),
                        "cancelled" to JsonPrimitive(true),
                    ),
                ),
            )
        } catch (e: EngineException) {
            log.w(e) { "Pi dialog dismissal was not delivered" }
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
        permissions.clear()
        decisions.clear()
        if (isHandleClosed) release()
    }

    private suspend fun failed(failure: EngineFailure) = withContext(dispatchers.main) {
        if (state.value != ActiveSessionState.Closed) {
            machine.send(ActiveSessionIntent.Internal.Failed(turn?.id, failure))
        }
    }

    private suspend fun reconcile(snapshot: JsonObject) {
        if (snapshot.string("sessionId") != nativeRef?.nativeId) {
            piFailure(EngineFailure.Session(SessionFailureReason.Changed))
        }
        val model = snapshot["model"] as? JsonObject
            ?: piFailure(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
        val provider = model.string("provider")
        val id = model.string("id")
        if (provider != request.target.model.value.substringBefore("/") || id.isNullOrBlank()) {
            piFailure(EngineFailure.Access(AccessFailureReason.ModelAccessDenied))
        }
        target = target.copy(model = ModelId("$provider/$id"))
        val isBusy = (snapshot["isStreaming"] as? JsonPrimitive)?.booleanOrNull == true ||
            (snapshot["isCompacting"] as? JsonPrimitive)?.booleanOrNull == true
        val remembered = turn
        if (isBusy && remembered == null) piFailure(EngineFailure.Session(SessionFailureReason.Changed))
        val completed = if (!isBusy && remembered != null) {
            ActiveSessionIntent.Internal.Finished(remembered.id, TurnOutcome.Unknown)
        } else {
            null
        }
        val pending = if (isBusy) permissions.values.filter { it.turn == remembered?.id } else emptyList()
        val intent = ActiveSessionIntent.Internal.Synchronized(
            if (isBusy) remembered else null,
            pending,
            completed,
        )
        if (machine.send(intent) != SendResult.Accepted) return
        if (completed != null) {
            journal.finished(completed.turn, completed.outcome)
            turn = null
            permissions.clear()
            decisions.clear()
        }
    }

    private companion object {
        // Must match APPROVAL_TITLE in resources/pi/heartbeat-approval.ts.
        const val APPROVAL_TITLE = "heartbeat.tool-approval"
        const val APPROVAL_TARGET_LIMIT = 4_000
        const val HEX_RADIX = 16
        const val HEX_DIGITS = 4
        val DIALOG_METHODS = setOf("select", "confirm", "input", "editor")
        val AllowOption = PermissionOptionId("allow")
        val DenyOption = PermissionOptionId("deny")
    }

    /** The prompt never reached Pi, so the failure is definite rather than an unknown delivery. */
    private class PromptNotSentException(val failure: EngineFailure, cause: EngineException) :
        Exception(failure.code, cause)

    /** Makes line breaks, control and bidirectional formatting characters visible in the approval text. */
    private fun visible(text: String): String = buildString {
        text.forEach { char ->
            when {
                char == '\n' -> append("\\n")

                char == '\r' -> append("\\r")

                char == '\t' -> append("\\t")

                Character.isISOControl(char) || Character.getType(char) == Character.FORMAT.toInt() ->
                    append("\\u").append(char.code.toString(HEX_RADIX).padStart(HEX_DIGITS, '0'))

                else -> append(char)
            }
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
