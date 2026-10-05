package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionEffect
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionMachineKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AppliesTrustLevels
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ChangesSessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfigurationChange
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SwitchesModels
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.activeSessionMachineSpec
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiActiveSession
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
import java.util.UUID

internal typealias PiConnector =
    suspend (event: suspend (JsonObject) -> Unit, failed: suspend (EngineFailure) -> Unit) -> PiConnection

/** Session source of transcripts kept in the profile's Pi session directory. */
internal val PiSessionSource: SessionSourceId = SessionSourceId("pi.profile")

/** Stored native session [ref] and its transcript [file]; the path is never logged. */
internal data class PiTranscript(val ref: SessionRef, val file: String)

/**
 * One native Pi session over a dedicated process. Turn completion follows Pi's `agent_settled` event
 * (`docs/rpc.md`: `agent_end` may be followed by retries). No extension commands are registered, so every
 * accepted prompt produces an agent run. The process is released once the handle is closed and no turn runs;
 * [released] lets the owning runtime forget this session.
 */
internal class PiSession(
    request: CreateSessionRequest,
    override val route: ExecutionRoute,
    private val environment: PiSessionEnvironment,
    private val validate: suspend () -> Unit,
    private val released: (PiSession) -> Unit = {},
) : PiActiveSession,
    SendsPrompts,
    CancelsTurns,
    SwitchesModels,
    ReconcilesSession,
    RequestsPermissions,
    AppliesTrustLevels,
    ChangesSessionConfiguration {
    private val log = Log.tag("PiSession")
    private val profile get() = environment.profile
    private val dispatchers get() = environment.dispatchers
    private val handle = environment.scopes.child(profile, "pi-" + UUID.randomUUID())
    private val promptResources = PiPromptResources(environment)
    private val journal = PiJournal(promptResources::originals)
    private val mutex = Mutex()
    private var connection: PiConnection? = null
    private var connector: PiConnector? = null
    private var nativeRef: SessionRef? = null
    private val usage = PiSessionUsage(environment, handle)

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
    private var turn: Turn? = null

    // Policy for future tool approvals; pending requests keep their original decision. Confined to main.
    private var trust: TrustLevel = DefaultTrust
    private val sessionConfiguration = PiSessionConfiguration(
        request.target,
        { nativeRef?.nativeId },
        ::rpc,
        { trust },
        { model ->
            usage.model(model, rpc().contextCapacity(model))
            promptResources.model(model)
        },
    )
    private val target get() = sessionConfiguration.target
    override val configuration = sessionConfiguration.configuration
    private var terminal: TurnOutcome = TurnOutcome.Completed
    private var acceptance: CompletableDeferred<TurnId>? = null
    private var cancellationAck: CompletableDeferred<Unit>? = null

    // Pending tool approvals keyed by the Pi extension UI request id; confined to dispatchers.main.
    private val permissions = mutableMapOf<PermissionRequestId, PermissionRequest>()
    private val decisions = mutableSetOf<PermissionRequestId>()

    // Non-approval dialogs among [permissions] and how to answer them; confined to dispatchers.main.
    private val dialogs = mutableMapOf<PermissionRequestId, PiDialog>()
    private val machine = environment.machines.launch(
        activeSessionMachineSpec(ActiveSessionMachineKey(UUID.randomUUID().toString()), ActiveSessionState.Ready()),
        handle,
        EffectHandler<ActiveSessionEffect, ActiveSessionIntent> { effect, _ ->
            // Accepted work belongs to the profile, never to this state-scoped effect or the caller.
            if (effect is ActiveSessionEffect.Decide) decide(effect.decision) else handoff(effect)
        },
    )

    // Hosted tools are prepared once per process from the request that launched it.
    private val launchTarget = request.target
    private val areDetachedToolsEnabled = request.areDetachedToolsEnabled
    private val hostedTools = PiHostedSessionTools(
        environment,
        { state.value is ActiveSessionState.Interrupting },
        { active -> !isHandleClosed && turn?.id == active.id },
        permissions,
        { machine.send(it) },
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
    override val contextUsage: SessionContextUsage get() = usage
    override val features: EngineFeatures = piSessionFeatures(this, journal) { promptResources.support }

    /** Native session of this handle once started; null before [start] succeeds. */
    val attachedRef: SessionRef? get() = nativeRef

    suspend fun prepareHostedTools(): PiHostedTools? =
        hostedTools.prepare(route.workspace, launchTarget, areDetachedToolsEnabled)

    /** Starts Pi on a new native session, or on the stored [transcript] when the session is resumed. */
    suspend fun start(factory: PiConnector, transcript: PiTranscript? = null): Unit = withContext(dispatchers.main) {
        var isStarted = false
        try {
            connector = factory
            withContext(NonCancellable) { connection = open(factory) }
            currentCoroutineContext().ensureActive()
            val stored = transcript?.let { rpc().reattach(it.file).storedConversation() }
            rpc().command("set_model", sessionConfiguration.modelFields(target.model))
            val snapshot = rpc().command("get_state")
            usage.model(snapshot["model"] as? JsonObject, rpc().contextCapacity(snapshot["model"] as? JsonObject))
            promptResources.model(snapshot["model"] as? JsonObject)
            val nativeId = snapshot.string("sessionId")
                ?: piFailure(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
            // Pi must sit on exactly the stored transcript; any other session is never adopted silently.
            if (transcript != null && transcript.ref.nativeId != nativeId) {
                piFailure(EngineFailure.Session(SessionFailureReason.Changed))
            }
            // The journal only observes live events; a resumed session starts from the stored conversation.
            nativeRef = SessionRef(route.engine, PiSessionSource, nativeId)
            stored?.let { promptResources.restore(ref, it) }
            stored?.let(journal::restore)
            sessionFile = snapshot.string("sessionFile")
            sessionConfiguration.start(snapshot)
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
                piValidatePromptRequest(request)
                promptResources.prepare(ref, request)
                val next = Turn(TurnId(UUID.randomUUID().toString()), request.id, target)
                val result = CompletableDeferred<TurnId>()
                val previous = Triple(turn, acceptance, isTurnStarted)
                val previousTrust = trust
                prepare(ActiveSessionEffect.Submit(request, next))
                acceptance = result
                turn = next
                hostedTools.beginTurn()
                trust = request.trust ?: DefaultTrust
                hostedTools.capture(ref, route.workspace, next, trust)
                isTurnStarted = false
                terminal = TurnOutcome.Completed
                if (machine.send(ActiveSessionIntent.Public.Submit(request, next)) != SendResult.Accepted) {
                    hostedTools.revoke()
                    isCommandPending = false
                    pendingEffect = null
                    turn = previous.first
                    acceptance = previous.second
                    isTurnStarted = previous.third
                    trust = previousTrust
                    piFailure(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed))
                }
                sessionConfiguration.publishTrust()
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
            changeConfiguration(SessionConfigurationChange.Model(model))
        }
    }

    override suspend fun apply(operationId: String, change: SessionConfigurationChange): SessionConfiguration =
        withContext(NonCancellable + dispatchers.main) {
            mutex.withLock {
                validate()
                ensureOpen()
                if (isCommandPending) piFailure(EngineFailure.Session(SessionFailureReason.Busy))
                state.value.ensureConfigurationAllowed()
                log.i { "Applying live Pi configuration: ${change::class.simpleName.orEmpty()}" }
                changeConfiguration(change)
            }
        }

    /** Native setters update the next model request; outstanding tool approvals keep their original decision. */
    private suspend fun changeConfiguration(change: SessionConfigurationChange): SessionConfiguration {
        try {
            return sessionConfiguration.apply(change) {
                trust = it
                hostedTools.updateTrust(it)
            }
        } catch (e: EngineException) {
            log.w(e) { "Pi configuration change was not acknowledged" }
            // A definite refusal keeps the turn usable; loss of native confirmation requires reconciliation.
            if (e.failure !is EngineFailure.Request && e.failure !is EngineFailure.Access) failed(e.failure)
            throw e
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
            usage.close()
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
        usage.close()
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
        hostedTools.close()
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
        usage.clear()
        // Approvals belonged to the lost process; its extension can no longer receive an answer.
        permissions.clear()
        dialogs.clear()
        decisions.clear()
        val file = sessionFile ?: piFailure(EngineFailure.Session(SessionFailureReason.NotResumable))
        val factory = connector ?: piFailure(EngineFailure.Engine(EngineFailureReason.Unavailable))
        log.i { "Restarting Pi process for session recovery" }
        val fresh = open(factory)
        val snapshot = try {
            fresh.reattach(file)
            // Pi can acknowledge switch_session by creating a new session when the file was never persisted.
            // Verify identity before publishing the connection, otherwise this handle could keep that process.
            val snapshot = fresh.command("get_state")
            if (snapshot.string("sessionId") != nativeRef?.nativeId) {
                piFailure(EngineFailure.Session(SessionFailureReason.Changed))
            }
            snapshot
        } catch (e: EngineException) {
            // Never keep a process that sits on a different transcript than this handle.
            log.w(e) { "Pi session recovery could not reattach the transcript" }
            generation++
            fresh.close()
            throw e
        }
        connection = fresh
        val model = snapshot["model"] as? JsonObject
        usage.model(model, fresh.contextCapacity(model))
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
                    hostedTools.revoke()
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
                hostedTools.revoke()
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
        promptResources.submit(rpc(), sessionConfiguration, effect.request)
        if (turn?.id == effect.turn.id) machine.send(ActiveSessionIntent.Internal.Accepted(effect.turn.id))
        started(effect.turn)
        accepted?.complete(effect.turn.id)
    }

    private suspend fun event(record: JsonObject) = withContext(dispatchers.main) {
        usage.event(record)
        // Configuration events can arrive before get_state establishes the native identity.
        nativeRef?.let { promptResources.event(it, record) }
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
        if (record.string("method") == "confirm" && record.string("title") == APPROVAL_TITLE) {
            approval(id, record.string("message"))
        } else {
            val dialog = turn?.let { PiDialog.from(record, id, it.id) }
            await(id, dialog?.request, dialog)
        }
    }

    private suspend fun approval(id: String, message: String?) {
        val call = approvalCall(message)
        val level = trust
        if (call != null && isTrusted(call, level) && canAnswerAlone()) {
            allow(id, call.tool, level)
        } else {
            val active = turn
            await(id, if (call != null && active != null) approvalRequest(id, active.id, call) else null, null)
        }
    }

    /** Surfaces [request] to the user; a malformed request or one nobody can answer is dismissed. */
    private suspend fun await(id: String, request: PermissionRequest?, dialog: PiDialog?) {
        if (request == null || isHandleClosed) {
            dismiss(id)
            return
        }
        permissions[request.id] = request
        dialog?.let { dialogs[request.id] = it }
        if (machine.send(ActiveSessionIntent.Internal.PermissionNeeded(request)) == SendResult.Accepted) {
            log.i { if (dialog == null) "Pi tool call awaits user approval" else "Pi dialog awaits user answer" }
        } else {
            permissions.remove(request.id)
            dialogs.remove(request.id)
            dismiss(id)
        }
    }

    /** Whether the trust of the running turn covers [call]; nothing is trusted while the turn is interrupted. */
    private suspend fun isTrusted(call: PiApprovalCall, level: TrustLevel): Boolean {
        if (!canAnswerAlone()) return false
        val workspace = connection?.workingDirectory
        return withContext(dispatchers.io) { level.answers(call, workspace) }
    }

    private fun canAnswerAlone(): Boolean =
        turn != null && !isHandleClosed && machine.state.value !is ActiveSessionState.Interrupting

    /** Answers an approval the turn's trust covers; Pi runs the tool as if the user allowed it. */
    private suspend fun allow(id: String, tool: String, level: TrustLevel) {
        try {
            rpc().respondToUi(id, "confirmed" to JsonPrimitive(true))
            log.i { "Pi tool call allowed by trust level $level: $tool" }
        } catch (e: EngineException) {
            log.w(e) { "Pi trusted approval was not delivered" }
            if (turn != null) failed(e.failure)
        }
    }

    private fun decide(decision: PermissionDecision) {
        if (decisions.add(decision.request)) {
            profile.coroutineScope.launch(dispatchers.main) { answer(decision) }
        }
    }

    private suspend fun answer(decision: PermissionDecision) {
        val request = permissions.remove(decision.request) ?: return
        if (hostedTools.answer(decision)) return
        val dialog = dialogs.remove(decision.request)
        try {
            val isAllowed = decision.option == PiApprovalAllow
            val reply = dialog?.reply(decision) ?: ("confirmed" to JsonPrimitive(isAllowed))
            rpc().respondToUi(decision.request.value, reply)
            // Pi does not acknowledge dialog answers; handing the answer to the process resolves the request.
            machine.send(ActiveSessionIntent.Internal.PermissionResolved(decision.turn, decision.request))
            log.i {
                when {
                    dialog != null -> "Pi dialog answered by user: ${reply.first}"
                    isAllowed -> "Pi tool call allowed by user"
                    else -> "Pi tool call denied by user"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "Pi approval answer was not delivered" }
            decisions.remove(decision.request)
            // The request is still pending in Pi: keep it answerable so a retry can deliver the answer.
            permissions[decision.request] = request
            dialog?.let { dialogs[decision.request] = it }
            if (turn?.id == decision.turn) failed(e.failure)
        }
    }

    private suspend fun dismissApprovals() {
        val pending = permissions.keys.filter { !hostedTools.contains(it) }
        hostedTools.dismiss()
        permissions.clear()
        dialogs.clear()
        pending.forEach { dismiss(it.value) }
    }

    /** Declines a dialog nobody can answer; for an approval this blocks the tool call. */
    private suspend fun dismiss(id: String) {
        try {
            connection?.takeIf { it.isOpen }?.respondToUi(id, "cancelled" to JsonPrimitive(true))
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
        hostedTools.revoke()
        started(completed)
        acceptance?.complete(completed.id)
        if (state.value != ActiveSessionState.Closed) {
            machine.send(
                ActiveSessionIntent.Internal.Finished(completed.id, outcome),
            )
        }
        journal.finished(completed.id, outcome)
        turn = null
        hostedTools.dismiss()
        permissions.clear()
        dialogs.clear()
        decisions.clear()
        if (isHandleClosed) release()
    }

    private suspend fun failed(failure: EngineFailure) = withContext(dispatchers.main) {
        hostedTools.revoke()
        if (state.value != ActiveSessionState.Closed) {
            machine.send(ActiveSessionIntent.Internal.Failed(turn?.id, failure))
        }
    }

    private suspend fun reconcile(snapshot: JsonObject) {
        sessionConfiguration.confirm(snapshot)
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
            hostedTools.revoke()
            journal.finished(completed.turn, completed.outcome)
            turn = null
            permissions.clear()
            dialogs.clear()
            decisions.clear()
        }
    }

    private companion object {
        // Must match APPROVAL_TITLE in resources/pi/heartbeat-approval.ts.
        const val APPROVAL_TITLE = "heartbeat.tool-approval"
        val DIALOG_METHODS = setOf("select", "confirm", "input", "editor")

        // Prompts without an explicit trust keep every action behind a user decision.
        val DefaultTrust = TrustLevel.Ask
    }

    /** The prompt never reached Pi, so the failure is definite rather than an unknown delivery. */
    private class PromptNotSentException(val failure: EngineFailure, cause: EngineException) :
        Exception(failure.code, cause)

    private fun ensureOpen() {
        if (state.value is ActiveSessionState.Closing || state.value == ActiveSessionState.Closed) {
            piFailure(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed))
        }
    }

    private fun rpc(): PiConnection = connection
        ?: piFailure(EngineFailure.Engine(EngineFailureReason.Unavailable))
}

/** Switches this connection to the native transcript [file]; Pi reports a refused switch as `cancelled`. */
private suspend fun PiConnection.reattach(file: String): PiConnection = apply {
    val switched = command("switch_session", JsonObject(mapOf("sessionPath" to JsonPrimitive(file))))
    if ((switched["cancelled"] as? JsonPrimitive)?.booleanOrNull == true) {
        piFailure(EngineFailure.Session(SessionFailureReason.Changed))
    }
}

/** Live native settings are available between requests of the current turn, including a pending approval. */
private fun ActiveSessionState.ensureConfigurationAllowed() {
    when (this) {
        is ActiveSessionState.Ready, is ActiveSessionState.Running, is ActiveSessionState.AwaitingUserAction -> Unit

        is ActiveSessionState.Unavailable -> piFailure(failure)

        is ActiveSessionState.Submitting, is ActiveSessionState.Interrupting ->
            piFailure(EngineFailure.Session(SessionFailureReason.Busy))

        is ActiveSessionState.Closing, ActiveSessionState.Closed ->
            piFailure(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed))
    }
}
