package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AcceptsImages
import io.aequicor.heartbeat.feature.aiengine.facade.api.AcceptsResources
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.AppliesTrustLevels
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineUsageEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptResourceHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngineTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** Session commands are serialized across leases; generation belongs to the runtime's supervisor. */
internal class ClaudeSession(
    val ref: SessionRef,
    val route: ExecutionRoute,
    val target: EngineTarget,
    private val environment: ClaudeSessionEnvironment,
    restored: ClaudeRecord? = null,
) {
    private val transport get() = environment.transport
    private val account get() = environment.account
    private val toggles get() = environment.toggles
    private val scope get() = environment.scope
    private val log = Log.tag("ClaudeSession")
    private val commands = Mutex()
    private val lock = Any()
    private val history = ClaudeHistory(restored?.history ?: ClaudeHistorySnapshot())

    /**
     * Hosted tools for a session without a project. Chosen by the lease that opens the session without other holders;
     * only callers answering hosted permissions enable it.
     */
    @Volatile
    private var areDetachedToolsEnabled: Boolean = false
    private val storage = Mutex()
    val contextUsage = ClaudeContextUsage()
    private val leases = mutableSetOf<Lease>()
    private var current: ActiveSessionState = ActiveSessionState.Ready(
        restored?.activeTurn?.let {
            val outcome = it.outcome ?: if (it.id == restored.undelivered) {
                TurnOutcome.Failed(LAUNCH_FAILURE)
            } else {
                TurnOutcome.Unknown
            }
            if (it.outcome == null) {
                history.publish { checkpoint ->
                    SessionEvent.TurnFinished(checkpoint, it.id, outcome)
                }
            }
            it.copy(outcome = outcome)
        } ?: restored?.lastTurn,
    )
    private var launch = restored?.launch ?: ClaudeLaunch.Prepared

    @Volatile
    private var permissions: ClaudePermissions? = null

    @Volatile
    private var cancelledTurn: TurnId? = null

    @Volatile
    private var operation: Job? = null

    @Volatile
    private var hasNativeSession = restored?.launch == ClaudeLaunch.Confirmed

    /** Set once the runtime dropped this released session; no new lease may be opened on it. */
    private var isEvicted = false

    /** A turn whose CLI process never started: the prompt was certainly not delivered. */
    @Volatile
    private var undelivered: TurnId? = null

    /**
     * Attaches a handle. Without other holders [areDetachedToolsEnabled] decides hosted tools for later turns of a
     * session without a project; an attach to a session that is already held keeps the holders' choice.
     */
    fun lease(areDetachedToolsEnabled: Boolean): ActiveSession = synchronized(lock) {
        ensureOpen()
        if (isEvicted) throw EngineException(EngineFailure.Session(SessionFailureReason.NotResumable))
        if (leases.isEmpty()) this.areDetachedToolsEnabled = areDetachedToolsEnabled
        Lease(current).also { leases.add(it) }
    }

    /** No handle is attached and no turn is running: the runtime may drop the session. */
    val isReleased: Boolean get() = synchronized(lock) { leases.isEmpty() && operation?.isActive != true }

    /** Atomically marks a released session as dropped; returns false if it was leased or busy meanwhile. */
    fun evict(): Boolean = synchronized(lock) {
        if (!isEvicted && leases.isEmpty() && operation?.isActive != true) {
            log.d { "Evicting released Claude session" }
            isEvicted = true
            history.close()
        }
        isEvicted
    }

    fun stored(attachSession: suspend (ResumeSessionRequest) -> ActiveSession): EngineSession = object :
        EngineSession {
        override val summary = MutableStateFlow(
            SessionSummary(
                ref,
                workspace = route.workspace,
                origin = SessionOrigin.Heartbeat,
                lastRoute = route,
                features = setOf(SessionHistory.id, ResumesSessions.id),
            ),
        ).asStateFlow()
        private val resumption = object : ResumesSessions {
            override suspend fun resume(request: ResumeSessionRequest) = attachSession(request)
        }
        override val features = ClaudeFeatures(
            SessionHistory to history,
            ResumesSessions to resumption,
        )
    }

    /** [failure] explains why the owning runtime stopped: profile closed, or its account was replaced. */
    fun shutdown(failure: EngineFailure) = synchronized(lock) {
        contextUsage.clear()
        val unfinished = when (val state = current) {
            is ActiveSessionState.Submitting -> state.turn
            is ActiveSessionState.Running -> state.turn
            is ActiveSessionState.AwaitingUserAction -> state.turn
            is ActiveSessionState.Interrupting -> state.turn
            is ActiveSessionState.Unavailable -> state.activeTurn
            else -> null
        }
        val last = (current as? ActiveSessionState.Ready)?.lastTurn
            ?: (current as? ActiveSessionState.Unavailable)?.lastTurn
        unfinished?.takeIf { it.outcome == null }?.let { turn ->
            val outcome = displacedOutcome(turn)
            history.publish { SessionEvent.TurnFinished(it, turn.id, outcome) }
        }
        update(
            ActiveSessionState.Unavailable(
                failure,
                // A displaced turn is the latest one, even when an earlier turn was remembered as last.
                lastTurn = unfinished?.let { it.copy(outcome = it.outcome ?: displacedOutcome(it)) } ?: last,
            ),
        )
        history.close()
    }

    private suspend fun send(request: PromptRequest, lease: Lease): TurnId {
        if (!commands.tryLock()) busy()
        val accepted = CompletableDeferred<TurnId>()
        try {
            lease.ensureAttached()
            ensureOpen()
            if (synchronized(lock) { current !is ActiveSessionState.Ready }) busy()
            awaitSettled()
            val isEffortKnown = request.reasoningEffort?.let { it in ClaudeEffortLevels } ?: true
            if (!isEffortKnown) throw EngineException(EngineFailure.Request(RequestFailureReason.Invalid, request.id))
            val prepared = claudePromptInputs(request, environment.inputSupport(target.model), environment.resources)
            val text = if (request.parts.all { it is ContentPart.Text }) {
                promptText(request)
            } else {
                kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("user"))
                    put(
                        "message",
                        kotlinx.serialization.json.buildJsonObject {
                            put("role", kotlinx.serialization.json.JsonPrimitive("user"))
                            put("content", kotlinx.serialization.json.JsonArray(prepared.blocks))
                        },
                    )
                    put("parent_tool_use_id", kotlinx.serialization.json.JsonNull)
                }.toString() + "\n"
            }
            environment.resourceHistory.remember(ref, "request:" + request.id.value, request.parts)
            requireClaudeEnabled(toggles)
            account.validate(route.revision)
            val turn = Turn(TurnId(UUID.randomUUID().toString()), request.id, target)
            environment.resourceHistory.remember(ref, turn.id.value, request.parts)
            synchronized(lock) {
                lease.ensureAttached()
                ensureOpen()
                val previous = (current as? ActiveSessionState.Ready)?.lastTurn
                update(ActiveSessionState.Submitting(request, turn))
                operation = scope.launch {
                    execute(Submission(request, text, turn, previous), accepted)
                }
                // The CLI may already have read the prompt when the runtime stops, so delivery is unknown.
                operation?.invokeOnCompletion { cause ->
                    if (cause != null && !accepted.isCompleted) {
                        accepted.completeExceptionally(
                            EngineException(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, request.id)),
                        )
                    }
                }
            }
            // Registered outside the session lock: a turn ending without handles releases the session.
            operation?.invokeOnCompletion { environment.onReleased(this) }
        } finally {
            commands.unlock()
        }
        return accepted.await()
    }

    private suspend fun execute(submission: Submission, accepted: CompletableDeferred<TurnId>) {
        val observer = ClaudeTurnObserver(ref, submission.turn, submission.request, history, accepted, ::update)
        val hosted = hostedTurn(submission, observer)
        permissions = hosted.permissions
        val previousLaunch = launch
        var isTransportInvoked = false
        try {
            log.i { "Submitting Claude prompt effort=${submission.request.reasoningEffort ?: "default"}" }
            val tools = hosted.prepare()
            val isResume = launch != ClaudeLaunch.Prepared
            launch = ClaudeLaunch.Attempted
            persist()
            isTransportInvoked = true
            val exit = transport.run(
                promptArguments(submission.request, isResume),
                submission.text,
                route.workspace,
                hosted = tools,
            ) {
                receive(observer, it)
                false
            }
            log.i { "Claude prompt process ended exit=$exit" }
            if (observer.isFinished) {
                finishObserved(observer, isConfirmed = exit == 0)
            } else {
                settle(submission, observer, accepted, EngineFailure.Engine(EngineFailureReason.Crashed))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e.redacted()) { "Claude turn observation failed" }
            val failure = if (!isTransportInvoked) {
                LAUNCH_FAILURE
            } else {
                (e as? EngineException)?.failure ?: EngineFailure.Engine(EngineFailureReason.Crashed)
            }
            executionFailed(submission, observer, accepted, previousLaunch, failure)
        } finally {
            hosted.close()
            permissions = null
            executionEnded(submission, observer, accepted)
            if (observer.hasMatchingSession) launch = ClaudeLaunch.Confirmed
            hasNativeSession = hasNativeSession || observer.hasMatchingSession
            withContext(NonCancellable) { persist() }
        }
    }

    private suspend fun hostedTurn(submission: Submission, observer: ClaudeTurnObserver): ClaudeHostedTurn =
        ClaudeHostedTurn(
            ClaudeTurnContext(
                ref,
                route.workspace,
                submission.request,
                submission.turn.target,
                areDetachedToolsEnabled,
            ),
            observer,
            history,
            environment,
            currentCoroutineContext()[Job],
            ClaudeTurnCallbacks(
                { isToolTurnActive(submission.turn.id) },
                ::update,
                ::persist,
                { synchronized(lock) { leases.isNotEmpty() } },
            ),
        )

    private fun isToolTurnActive(id: TurnId): Boolean {
        if (!scope.isActive || cancelledTurn == id) return false
        return synchronized(lock) {
            when (val state = current) {
                is ActiveSessionState.Submitting -> state.turn.id == id

                is ActiveSessionState.Running -> state.turn.id == id

                is ActiveSessionState.AwaitingUserAction -> state.turn.id == id

                is ActiveSessionState.Ready, is ActiveSessionState.Unavailable,
                is ActiveSessionState.Closed, is ActiveSessionState.Closing,
                is ActiveSessionState.Interrupting,
                -> false
            }
        }
    }

    private suspend fun promptArguments(request: PromptRequest, isResume: Boolean): List<String> {
        val arguments = claudeArguments(
            target.model,
            ref.nativeId,
            isResume,
            search = toggles.get(SearchEngineTools),
            effort = request.reasoningEffort,
        )
        return if (request.parts.any { it !is ContentPart.Text }) {
            arguments + listOf("--input-format", "stream-json")
        } else {
            arguments
        }
    }

    private suspend fun receive(observer: ClaudeTurnObserver, line: String) {
        val message = parseClaudeObject(line)
        observer.receive(message)
        if (observer.hasMatchingSession) launch = ClaudeLaunch.Confirmed
        if (toggles.get(EngineUsageEnabled)) {
            contextUsage.receive(message)
            environment.onUsage(message)
        }
        persist()
    }

    private fun executionFailed(
        submission: Submission,
        observer: ClaudeTurnObserver,
        accepted: CompletableDeferred<TurnId>,
        previousLaunch: ClaudeLaunch,
        failure: EngineFailure,
    ) {
        if (failure == LAUNCH_FAILURE && !observer.hasSession) launch = previousLaunch
        if (observer.isFinished) {
            finishObserved(observer, isConfirmed = false)
        } else {
            settle(submission, observer, accepted, failure)
        }
    }

    /** A stopped process is certain after transport cleanup; profile shutdown keeps its outcome unknown. */
    private fun executionEnded(
        submission: Submission,
        observer: ClaudeTurnObserver,
        accepted: CompletableDeferred<TurnId>,
    ) {
        if (cancelledTurn == submission.turn.id && scope.isActive) {
            val turn = observer.turn.copy(outcome = TurnOutcome.Cancelled)
            history.publish { SessionEvent.TurnFinished(it, turn.id, TurnOutcome.Cancelled) }
            update(ActiveSessionState.Ready(turn))
            val failure = EngineFailure.Request(RequestFailureReason.OutcomeUnknown, submission.request.id)
            accepted.completeExceptionally(EngineException(failure))
        } else if (!observer.isFinished && !scope.isActive) {
            update(
                ActiveSessionState.Unavailable(
                    environment.closeFailure(),
                    activeTurn = observer.turn,
                    lastTurn = submission.previous,
                ),
            )
        }
    }

    /** The CLI exits nonzero after an error result; only a success needs a confirmed transport to be trusted. */
    private fun finishObserved(observer: ClaudeTurnObserver, isConfirmed: Boolean) {
        if (isConfirmed || observer.turn.outcome is TurnOutcome.Failed) {
            observer.complete()
        } else {
            log.w { "Claude result was not confirmed by the process; outcome is unknown" }
            observer.resultUnconfirmed()
        }
    }

    /**
     * A started process can leave delivery uncertain even before its first session frame. A launch failure
     * (`RequirementsNotMet`, no child existed) is certain non-delivery, so synchronize() may return to Ready.
     */
    private fun settle(
        submission: Submission,
        observer: ClaudeTurnObserver,
        accepted: CompletableDeferred<TurnId>,
        failure: EngineFailure,
    ) {
        if (observer.isFinished) return
        if (observer.hasSession) {
            lost(submission.request, observer.turn, accepted)
            return
        }
        if (failure == LAUNCH_FAILURE) {
            log.w { "Claude CLI could not be started; the prompt was not delivered" }
            undelivered = observer.turn.id
        } else {
            log.w { "Claude prompt failed before a session frame; delivery is unknown" }
        }
        update(ActiveSessionState.Unavailable(failure, activeTurn = observer.turn, lastTurn = submission.previous))
        accepted.completeExceptionally(EngineException(failure))
    }

    private fun lost(request: PromptRequest, turn: Turn, accepted: CompletableDeferred<TurnId>) {
        log.w { "Claude prompt outcome is unknown after the CLI started a session" }
        val failure = EngineFailure.Request(RequestFailureReason.OutcomeUnknown, request.id)
        update(ActiveSessionState.Unavailable(failure, activeTurn = turn))
        accepted.completeExceptionally(EngineException(failure))
    }

    private suspend fun reconcile(lease: Lease) = commands.withLock {
        lease.ensureAttached()
        awaitSettled()
        synchronized(lock) {
            lease.ensureAttached()
            ensureOpen()
            reconcileState()
        }
        persist()
    }

    /** Storage writes are serialized so an older frame never overwrites a newer checkpoint. */
    suspend fun persist() = storage.withLock {
        val record = synchronized(lock) {
            val state = current
            val active = when (state) {
                is ActiveSessionState.Submitting -> state.turn
                is ActiveSessionState.Running -> state.turn
                is ActiveSessionState.AwaitingUserAction -> state.turn
                is ActiveSessionState.Interrupting -> state.turn
                is ActiveSessionState.Unavailable -> state.activeTurn
                is ActiveSessionState.Ready, is ActiveSessionState.Closing, ActiveSessionState.Closed -> null
            }
            val last = when (state) {
                is ActiveSessionState.Ready -> state.lastTurn

                is ActiveSessionState.Unavailable -> state.lastTurn

                is ActiveSessionState.Submitting, is ActiveSessionState.Running,
                is ActiveSessionState.AwaitingUserAction, is ActiveSessionState.Interrupting,
                is ActiveSessionState.Closing, ActiveSessionState.Closed,
                -> null
            }
            ClaudeRecord(
                ref,
                route,
                target,
                launch,
                last,
                active,
                history.snapshot(),
                transport.nativeStore,
                undelivered,
            )
        }
        environment.catalog.save(record)
    }

    private suspend fun cancel(turn: TurnId, lease: Lease) = commands.withLock {
        lease.ensureAttached()
        ensureOpen()
        val pending = synchronized(lock) {
            val active = when (val state = current) {
                is ActiveSessionState.Running -> state.turn
                is ActiveSessionState.AwaitingUserAction -> state.turn
                is ActiveSessionState.Interrupting -> state.turn
                else -> null
            }
            if (active?.id != turn) return@withLock
            cancelledTurn = turn
            update(ActiveSessionState.Interrupting(active))
            operation
        }
        log.i { "Stopping the current Claude native process" }
        pending?.cancelAndJoin()
    }

    private fun reconcileState() {
        val unavailable = current as? ActiveSessionState.Unavailable ?: return
        val turn = unavailable.activeTurn
        val last = unavailable.lastTurn
        if (turn == null && last?.outcome != TurnOutcome.Unknown) {
            log.d { "Claude session has no unresolved turn" }
            return
        }
        if (turn != null && turn.id == undelivered) {
            log.i { "Claude turn was never delivered; recorded as failed" }
            val outcome = displacedOutcome(turn)
            undelivered = null
            history.publish { SessionEvent.TurnFinished(it, turn.id, outcome) }
            update(ActiveSessionState.Ready(turn.copy(outcome = outcome)))
            return
        }
        if (!hasNativeSession) throw EngineException(EngineFailure.Session(SessionFailureReason.NotResumable))
        if (turn == null) {
            update(ActiveSessionState.Ready(last))
            return
        }
        log.i { "Claude turn recorded with unknown outcome" }
        history.publish { SessionEvent.TurnFinished(it, turn.id, TurnOutcome.Unknown) }
        update(ActiveSessionState.Ready(turn.copy(outcome = TurnOutcome.Unknown)))
    }

    /**
     * A turn that never reached a CLI process failed locally; any other displaced turn has an unknown outcome.
     * Its TurnFinished may have no TurnStarted, because the CLI never accepted it.
     */
    private fun displacedOutcome(turn: Turn): TurnOutcome =
        if (turn.id == undelivered) TurnOutcome.Failed(LAUNCH_FAILURE) else TurnOutcome.Unknown

    /** Ready and Unavailable are published after the process ends; only the coroutine's tail may still run. */
    private suspend fun awaitSettled() {
        val pending = operation?.takeIf { it.isActive } ?: return
        val isSettled = synchronized(lock) {
            current is ActiveSessionState.Ready || current is ActiveSessionState.Unavailable
        }
        if (!isSettled) busy()
        log.d { "Waiting for the previous Claude turn to settle" }
        pending.join()
    }

    private fun update(state: ActiveSessionState): Unit = synchronized(lock) {
        if (current is ActiveSessionState.Interrupting &&
            (state is ActiveSessionState.Running || state is ActiveSessionState.AwaitingUserAction)
        ) {
            return
        }
        log.d { "Claude execution state=${state::class.simpleName.orEmpty()}" }
        current = state
        leases.forEach { it.update(state) }
    }

    private fun ensureOpen() {
        if (!scope.isActive) throw EngineException(environment.closeFailure())
    }

    private fun busy(): Nothing = throw EngineException(EngineFailure.Session(SessionFailureReason.Busy))

    private inner class Lease(initial: ActiveSessionState) :
        ActiveSession,
        SendsPrompts,
        ReconcilesSession,
        CancelsTurns,
        RequestsPermissions,
        AppliesTrustLevels {
        private val mutableState = MutableStateFlow(initial)

        @Volatile
        private var isDetached = false
        override val ref get() = this@ClaudeSession.ref
        override val route get() = this@ClaudeSession.route
        override val state = mutableState.asStateFlow()
        override val features = ClaudeFeatures(
            AcceptsImages to object : AcceptsImages {
                override val mediaTypes: Set<String> get() = environment.inputSupport(target.model).imageMediaTypes
            },
            AcceptsResources to object : AcceptsResources {
                override val mediaTypes: Set<String> get() = environment.inputSupport(target.model).resourceMediaTypes
            },
            SendsPrompts to this,
            SessionHistory to history,
            ReconcilesSession to this,
            SessionContextUsage to contextUsage,
            CancelsTurns to this,
            RequestsPermissions to this,
            AppliesTrustLevels to this,
        )

        override suspend fun cancel(turn: TurnId) {
            this@ClaudeSession.cancel(turn, this)
        }

        override suspend fun respond(decision: PermissionDecision) {
            ensureAttached()
            ensureOpen()
            permissions?.respond(decision)
        }
        override suspend fun send(request: PromptRequest): TurnId {
            log.i { "Sending Claude prompt" }
            return try {
                this@ClaudeSession.send(request, this)
            } catch (e: EngineException) {
                log.w(e.redacted()) { "Claude prompt failed: ${e.failure::class.simpleName.orEmpty()}" }
                throw e
            }
        }

        override suspend fun synchronize() {
            log.i { "Synchronizing Claude session" }
            try {
                reconcile(this)
            } catch (e: EngineException) {
                log.w(e.redacted()) { "Claude synchronization failed: ${e.failure::class.simpleName.orEmpty()}" }
                throw e
            }
        }

        override suspend fun close() {
            persist()
            val isLast = synchronized(lock) {
                log.i { "Detaching Claude session handle" }
                isDetached = true
                leases.remove(this)
                mutableState.value = ActiveSessionState.Closed
                leases.isEmpty()
            }
            // Outside the session lock: the runtime may lock other sessions while pruning.
            if (isLast) {
                permissions?.dismiss()
                environment.onReleased(this@ClaudeSession)
            }
        }

        fun ensureAttached() = synchronized(lock) {
            if (isDetached) throw EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed))
        }

        fun update(state: ActiveSessionState) {
            log.d { "Updating Claude session handle" }
            mutableState.value = state
        }
    }
}

private fun promptText(request: PromptRequest): String {
    if (request.parts.any { it !is ContentPart.Text }) {
        throw EngineException(EngineFailure.Request(RequestFailureReason.UnsupportedContent, request.id))
    }
    val text = request.parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }
    val isEffortKnown = request.reasoningEffort?.let { it in ClaudeEffortLevels } ?: true
    if (text.isBlank() || text.length > MAX_PROMPT_CHARS || !isEffortKnown) {
        throw EngineException(EngineFailure.Request(RequestFailureReason.Invalid, request.id))
    }
    return text
}

private const val MAX_PROMPT_CHARS = 1024 * 1024

/** Reported by the transport only before a child process exists. */
private val LAUNCH_FAILURE = EngineFailure.Engine(EngineFailureReason.RequirementsNotMet)

private data class Submission(val request: PromptRequest, val text: String, val turn: Turn, val previous: Turn?) {
    override fun toString(): String = "Submission(***)"
}

/** Dependencies shared by every session of one profile-owned runtime; [closeFailure] explains a stopped runtime. */
internal data class ClaudeSessionEnvironment(
    val transport: ClaudeTransport,
    val account: ClaudeAccount,
    val toggles: FeatureToggles,
    val scope: CoroutineScope,
    val closeFailure: () -> EngineFailure,
    val catalog: ClaudeCatalog,
    val tools: ProfileAgentTools,
    val bridge: AgentToolBridge,
    /** Called outside session locks when a session may have become released (last handle closed or turn ended). */
    val onReleased: (ClaudeSession) -> Unit = {},
    /** Native account-level quota events are shared across sessions of this runtime. */
    val onUsage: (JsonObject) -> Unit = {},
    val resources: ResourceResolver = ResourceResolver { null },
    val resourceHistory: PromptResourceHistory = PromptResourceHistory.None,
    val inputSupport: (ModelId) -> PromptInputSupport = { PromptInputSupport.TextDocuments },
)
