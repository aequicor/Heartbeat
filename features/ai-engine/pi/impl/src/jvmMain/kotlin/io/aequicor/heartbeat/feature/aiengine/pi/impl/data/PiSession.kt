package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionEffect
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionMachineKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
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
import java.util.UUID

internal typealias PiConnector =
    suspend (
        plan: PiLaunchPlan,
        event: suspend (JsonObject) -> Unit,
        failed: suspend (EngineFailure) -> Unit,
    ) -> PiConnection

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
    private val ownership: String,
    private val validate: suspend () -> Unit,
    private val released: (PiSession) -> Unit = {},
    private val restored: PiTurnSnapshot? = null,
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
    private var persistedTranscript: suspend (String) -> String? = { null }
    private var nativeRef: SessionRef? = null
    private val usage = PiSessionUsage(environment, handle)

    // Native transcript path, used only to reattach a restarted process; never logged.
    private var sessionFile: String? = null
    private var isHandleClosed = false
    private var isReleased = false
    private var isShuttingDown = false

    // Callbacks of a replaced or failed process are ignored once a newer connection generation exists.
    private var generation = 0
    private var isTurnStarted = false
    private var isCommandPending = false
    private var pendingEffect: ActiveSessionEffect? = null
    private var isEffectStarted = false
    private var turn: Turn? = restored?.active?.turn
    private val isExecutionOwned = restored?.active == null
    private val turnJournal get() = PiTurnJournal(environment.turns, ref, route, ownership)
    private val settlements = PiTurnSettlements({ turnJournal }, ::failed)
    private val isAdmissionBlocked get() = isCommandPending || settlements.isPublishing

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

    private val machine = environment.machines.launch(
        activeSessionMachineSpec(ActiveSessionMachineKey(UUID.randomUUID().toString()), restored.piInitialState()),
        handle,
        EffectHandler<ActiveSessionEffect, ActiveSessionIntent> { effect, _ ->
            // Accepted work belongs to the profile, never to this state-scoped effect or the caller.
            if (effect is ActiveSessionEffect.Decide) decide(effect.decision) else handoff(effect)
        },
    )

    private val sessionPermissions: PiSessionPermissions = PiSessionPermissions(
        { connection },
        { machine.send(it) },
        { isHandleClosed },
        { turn?.id == it },
        ::failed,
    )

    // Hosted tools are prepared once per process from the request that launched it.
    private val hostedTools = PiHostedSessionTools(
        environment,
        { state.value is ActiveSessionState.Interrupting },
        { active -> !isHandleClosed && turn?.id == active.id },
        sessionPermissions.permissions,
        { machine.send(it) },
    )
    private val nativeApprovals = PiNativeApprovals(environment, hostedTools, isCurrent = { process, active, captured ->
        val isSameProcess = generation == captured && connection === process && process.isOpen
        val isActiveTurn = turn?.id == active.id && !isHandleClosed
        isSameProcess && isActiveTurn && state.value !is ActiveSessionState.Interrupting
    }, failed = ::failed)
    private val toolPlans = PiToolPlans(environment, hostedTools, request.areDetachedToolsEnabled) {
        AgentToolScope(route.workspace, target, session = nativeRef)
    }
    private var launchedTools: Set<String> = emptySet()
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

    /** Starts Pi on a new native session, or on the stored [transcript] when the session is resumed. */
    suspend fun start(
        factory: PiConnector,
        transcript: PiTranscript? = null,
        persistedTranscript: suspend (String) -> String? = { null },
    ): Unit = withContext(dispatchers.main) {
        var isStarted = false
        try {
            connector = factory
            this@PiSession.persistedTranscript = persistedTranscript
            nativeRef = transcript?.ref
            if (transcript != null && turnJournal.restoreForOpening() != restored) {
                piFailure(EngineFailure.Session(SessionFailureReason.Changed))
            }
            withContext(NonCancellable) { connection = open(factory) }
            currentCoroutineContext().ensureActive()
            if (transcript != null) turnJournal.restoreForOpening()
            val opening = rpc().startSession(sessionConfiguration, transcript)
            val snapshot = opening.snapshot
            val stored = opening.branch
            usage.model(snapshot["model"] as? JsonObject, rpc().contextCapacity(snapshot["model"] as? JsonObject))
            promptResources.model(snapshot["model"] as? JsonObject)
            // The journal only observes live events; a resumed session starts from the stored conversation.
            nativeRef = SessionRef(route.engine, PiSessionSource, opening.nativeId)
            if (transcript == null && turnJournal.restore() != null) {
                piFailure(EngineFailure.Session(SessionFailureReason.Changed))
            }
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
                if (isAdmissionBlocked || state.value !is ActiveSessionState.Ready) {
                    piFailure(EngineFailure.Session(SessionFailureReason.Busy))
                }
                piValidatePromptRequest(request)
                refreshTools()
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
                if (isAdmissionBlocked) piFailure(EngineFailure.Session(SessionFailureReason.Busy))
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
            if (isAdmissionBlocked || state.value !is ActiveSessionState.Ready) {
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
                if (isAdmissionBlocked) piFailure(EngineFailure.Session(SessionFailureReason.Busy))
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
            if (isAdmissionBlocked) piFailure(EngineFailure.Session(SessionFailureReason.Busy))
            if (machine.send(ActiveSessionIntent.Public.Recheck) != SendResult.Accepted) {
                piFailure(EngineFailure.Request(RequestFailureReason.Invalid))
            }
            val snapshot = connected().command("get_state")
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
        isShuttingDown = true
        usage.close()
        try {
            val active = turn
            hostedTools.revoke()
            val isStopped = connection?.stopAndAwait() == true && isExecutionOwned
            if (active != null && isTurnStarted && isStopped) {
                try {
                    finish(TurnOutcome.Unknown)
                } catch (error: EngineException) {
                    log.w(error) { "Pi shutdown retained an unresolved durable turn" }
                }
            } else if (active != null) {
                acceptance?.completeExceptionally(
                    EngineException(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, active.request)),
                )
                turn = null
            }
        } finally {
            withContext(NonCancellable) {
                machine.send(ActiveSessionIntent.Public.Close)
                machine.send(ActiveSessionIntent.Internal.Released)
                acceptance?.completeExceptionally(
                    EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed)),
                )
                handle.close()
                isHandleClosed = true
                release()
            }
        }
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
        val plan = toolPlans.launch()
        ensureOpen()
        if (nativeRef != null) turnJournal.restoreForOpening()
        val current = ++generation
        val fresh = factory(
            plan,
            { record -> withContext(dispatchers.main) { if (!isShuttingDown && current == generation) event(record) } },
            { failure ->
                withContext(dispatchers.main) { if (!isShuttingDown && current == generation) failed(failure) }
            },
        )
        launchedTools = plan.requestedNames
        return fresh
    }

    /** Only growth needs a restart, between turns and under the send mutex, on the same saved transcript. */
    private suspend fun refreshTools() {
        val process = connection?.takeIf { it.isOpen } ?: return
        if ((toolPlans.desired().names - launchedTools).isEmpty()) return
        // get_state may advertise an allocated path before Pi writes its first assistant message.
        // Only a transcript found in profile storage is safe to reattach after closing this process.
        val file = nativeRef?.nativeId?.let { persistedTranscript(it) } ?: return
        sessionFile = file
        log.i { "Restarting Pi between turns to expose additional tools" }
        process.close()
        connected()
    }

    /** Returns the live connection, restarting Pi on the same native transcript after process loss. */
    private suspend fun connected(): PiConnection {
        connection?.takeIf { it.isOpen }?.let { return it }
        val previous = connection
        if (previous != null && !previous.stopAndAwait()) {
            piFailure(EngineFailure.Session(SessionFailureReason.NotResumable))
        }
        ensureOpen()
        return withContext(NonCancellable) {
            connection = null
            usage.clear()
            // Approvals belonged to the lost process; its extension can no longer receive an answer.
            sessionPermissions.clear()
            val file = sessionFile ?: piFailure(EngineFailure.Session(SessionFailureReason.NotResumable))
            val factory = connector ?: piFailure(EngineFailure.Engine(EngineFailureReason.Unavailable))
            log.i { "Restarting Pi process for session recovery" }
            val (fresh, snapshot) = sessionConfiguration.reconnect(
                file,
                nativeRef?.nativeId,
                open = { open(factory) },
                ensureOpen = {
                    ensureOpen()
                    turnJournal.restoreForOpening()
                },
                discarded = { generation++ },
            )
            connection = fresh
            val model = snapshot["model"] as? JsonObject
            usage.model(model, fresh.contextCapacity(model))
            fresh
        }
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
            turnJournal.finish(effect.turn.id, TurnOutcome.Failed(original))
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
        turnJournal.begin(effect.turn, effect.request.trust ?: DefaultTrust, rpc().processOwner())
        promptResources.submit(rpc(), sessionConfiguration, effect.request) {
            validate()
            ensureOpen()
        }
        if (turn?.id == effect.turn.id) machine.send(ActiveSessionIntent.Internal.Accepted(effect.turn.id))
        started(effect.turn)
        accepted?.complete(effect.turn.id)
    }

    private suspend fun event(record: JsonObject) = withContext(dispatchers.main) {
        // Events of this fresh process cannot describe the unresolved run owned by another process.
        if (!isExecutionOwned && turn != null) return@withContext
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

            "message_end" -> piMessageOutcome(record)?.let { terminal = it }

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
        } else if (record.string("method") == "input" && record.string("title") == "heartbeat.tool-result") {
            approval(id, record.string("placeholder"), isResult = true)
        } else {
            val dialog = turn?.let { PiDialog.from(record, id, it.id) }
            sessionPermissions.await(id, dialog)
        }
    }

    private suspend fun approval(id: String, message: String?, isResult: Boolean = false) {
        val call = approvalCall(message, isResult) ?: return dismiss(id)
        val active = turn ?: return dismiss(id)
        val process = connection ?: return dismiss(id)
        if (isHandleClosed || !nativeApprovals.launch(id, call, active, process, generation)) dismiss(id)
    }

    private fun decide(decision: PermissionDecision) {
        if (sessionPermissions.decisions.add(decision.request)) {
            profile.coroutineScope.launch(dispatchers.main) { sessionPermissions.answer(decision, hostedTools) }
        }
    }

    private suspend fun dismissApprovals() {
        val pending = sessionPermissions.permissions.keys.filter { !hostedTools.contains(it) }.toSet() +
            nativeApprovals.pending(generation)
        hostedTools.dismiss()
        sessionPermissions.clear(isClearingDecisions = false)
        pending.forEach { dismiss(it.value) }
    }

    private suspend fun dismiss(id: String) = sessionPermissions.dismiss(id)

    private fun started(accepted: Turn) {
        if (!isTurnStarted) {
            journal.started(accepted)
            isTurnStarted = true
        }
    }
    private suspend fun finish(outcome: TurnOutcome) {
        val completed = turn ?: return
        if (!isExecutionOwned) return
        hostedTools.revoke()
        settlements.publish(completed, outcome) { receipt ->
            if (turn?.id == completed.id) {
                val settled = checkNotNull(receipt.turn.outcome)
                started(completed)
                acceptance?.complete(completed.id)
                turn = null
                if (state.value != ActiveSessionState.Closed) {
                    machine.send(ActiveSessionIntent.Internal.Finished(completed.id, settled))
                }
                journal.finished(completed.id, settled)
                hostedTools.dismiss()
                sessionPermissions.clear()
                if (isHandleClosed) release()
            }
        }
    }

    private suspend fun failed(failure: EngineFailure): Unit = withContext(dispatchers.main) {
        hostedTools.revoke()
        if (state.value != ActiveSessionState.Closed) {
            machine.send(ActiveSessionIntent.Internal.Failed(turn?.id, failure))
        }
    }

    private suspend fun reconcile(snapshot: JsonObject) {
        sessionConfiguration.confirm(snapshot)
        val remembered = turn
        if (remembered != null && !isExecutionOwned) return
        val candidate = piReconciliation(snapshot, remembered, sessionPermissions.permissions.values)
        val completed = candidate.completed?.let { settlements.reconcile(it) }
        val intent = candidate.copy(completed = completed)
        if (machine.send(intent) != SendResult.Accepted) return
        if (completed != null) {
            hostedTools.revoke()
            journal.finished(completed.turn, completed.outcome)
            turn = null
            sessionPermissions.clear()
        }
    }

    private companion object {
        // Must match APPROVAL_TITLE in resources/pi/heartbeat-approval.ts.
        const val APPROVAL_TITLE = "heartbeat.tool-approval"
        val DIALOG_METHODS = setOf("select", "confirm", "input", "editor")

        // Prompts without an explicit trust keep every action behind a user decision.
        val DefaultTrust = TrustLevel.Ask
    }

    private fun ensureOpen() = state.value.ensurePiSessionOpen(isShuttingDown)

    private fun rpc(): PiConnection = connection
        ?: piFailure(EngineFailure.Engine(EngineFailureReason.Unavailable))
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

private fun ActiveSessionState.ensurePiSessionOpen(isShuttingDown: Boolean) {
    if (isShuttingDown || this is ActiveSessionState.Closing || this == ActiveSessionState.Closed) {
        piFailure(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed))
    }
}
