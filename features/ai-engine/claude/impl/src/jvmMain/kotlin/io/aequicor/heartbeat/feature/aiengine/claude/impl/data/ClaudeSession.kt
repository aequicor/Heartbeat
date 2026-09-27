package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Session commands are serialized across leases; generation belongs to the runtime's supervisor. */
internal class ClaudeSession(
    val ref: SessionRef,
    val route: ExecutionRoute,
    val target: EngineTarget,
    private val environment: ClaudeSessionEnvironment,
) {
    private val transport get() = environment.transport
    private val account get() = environment.account
    private val toggles get() = environment.toggles
    private val scope get() = environment.scope
    private val log = Log.tag("ClaudeSession")
    private val commands = Mutex()
    private val lock = Any()
    private val history = ClaudeHistory()
    private val leases = mutableSetOf<Lease>()
    private var current: ActiveSessionState = ActiveSessionState.Ready()
    private var operation: Job? = null

    @Volatile
    private var hasNativeSession = false

    fun lease(): ActiveSession = synchronized(lock) {
        ensureOpen()
        Lease(current).also { leases.add(it) }
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

    fun shutdown() = synchronized(lock) {
        val unfinished = when (val state = current) {
            is ActiveSessionState.Submitting -> state.turn
            is ActiveSessionState.Running -> state.turn
            is ActiveSessionState.Unavailable -> state.activeTurn
            else -> null
        }
        val last = (current as? ActiveSessionState.Ready)?.lastTurn
            ?: (current as? ActiveSessionState.Unavailable)?.lastTurn
        unfinished?.takeIf { it.outcome == null }?.let { turn ->
            history.publish { SessionEvent.TurnFinished(it, turn.id, TurnOutcome.Unknown) }
        }
        update(
            ActiveSessionState.Unavailable(
                EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed),
                lastTurn = last ?: unfinished?.copy(outcome = unfinished.outcome ?: TurnOutcome.Unknown),
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
            if (synchronized(lock) { current !is ActiveSessionState.Ready } || operation?.isActive == true) busy()
            val text = promptText(request)
            requireClaudeEnabled(toggles)
            account.validate(route.revision)
            synchronized(lock) {
                lease.ensureAttached()
                ensureOpen()
                val previous = (current as? ActiveSessionState.Ready)?.lastTurn
                val turn = Turn(TurnId(UUID.randomUUID().toString()), request.id, target)
                update(ActiveSessionState.Submitting(request, turn))
                operation = scope.launch {
                    execute(Submission(request, text, turn, previous), accepted)
                }
                operation?.invokeOnCompletion { cause ->
                    if (cause != null && !accepted.isCompleted) {
                        accepted.completeExceptionally(
                            EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed)),
                        )
                    }
                }
            }
        } finally {
            commands.unlock()
        }
        return accepted.await()
    }

    private suspend fun execute(submission: Submission, accepted: CompletableDeferred<TurnId>) {
        val request = submission.request
        val observer = ClaudeTurnObserver(ref, submission.turn, request, history, accepted, ::update)
        try {
            log.i { "Submitting Claude prompt" }
            val exit = transport.run(
                claudeArguments(target.model, ref.nativeId, hasNativeSession),
                submission.text,
                route.workspace,
            ) {
                observer.receive(parseClaudeObject(it))
                false
            }
            log.i { "Claude prompt process ended exit=$exit" }
            hasNativeSession = hasNativeSession || observer.hasSession
            settle(submission, observer, accepted, EngineFailure.Engine(EngineFailureReason.Crashed))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e.redacted()) { "Claude turn observation failed" }
            hasNativeSession = hasNativeSession || observer.hasSession
            val failure = (e as? EngineException)?.failure ?: EngineFailure.Engine(EngineFailureReason.Crashed)
            settle(submission, observer, accepted, failure)
        } finally {
            if (!observer.isFinished && !scope.isActive) {
                update(
                    ActiveSessionState.Unavailable(
                        EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed),
                        activeTurn = observer.turn,
                    ),
                )
            }
        }
    }

    /**
     * Without any session frame the CLI never started the native turn, so the prompt is rejected with the real
     * cause and the session stays usable. After a session frame the prompt may have been delivered: outcome unknown.
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
        log.w { "Claude prompt rejected before the CLI started a session" }
        update(ActiveSessionState.Ready(submission.previous))
        accepted.completeExceptionally(EngineException(failure))
    }

    private fun lost(request: PromptRequest, turn: Turn, accepted: CompletableDeferred<TurnId>) {
        val failure = EngineFailure.Request(RequestFailureReason.OutcomeUnknown, request.id)
        update(ActiveSessionState.Unavailable(failure, activeTurn = turn))
        accepted.completeExceptionally(EngineException(failure))
    }

    private suspend fun reconcile(lease: Lease) = commands.withLock {
        synchronized(lock) {
            lease.ensureAttached()
            ensureOpen()
            if (operation?.isActive == true) busy()
            val unavailable = current as? ActiveSessionState.Unavailable ?: return@withLock
            // Termination proves only that the local process stopped, not its native outcome.
            val turn = unavailable.activeTurn ?: return@withLock
            if (!hasNativeSession) {
                throw EngineException(EngineFailure.Session(SessionFailureReason.NotResumable))
            }
            history.publish { SessionEvent.TurnFinished(it, turn.id, TurnOutcome.Unknown) }
            update(ActiveSessionState.Ready(turn.copy(outcome = TurnOutcome.Unknown)))
        }
    }

    private fun update(state: ActiveSessionState) = synchronized(lock) {
        log.d { "Claude execution state=${state::class.simpleName.orEmpty()}" }
        current = state
        leases.forEach { it.update(state) }
    }

    private fun ensureOpen() {
        if (!scope.isActive) throw EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
    }

    private fun busy(): Nothing = throw EngineException(EngineFailure.Session(SessionFailureReason.Busy))

    private inner class Lease(initial: ActiveSessionState) :
        ActiveSession,
        SendsPrompts,
        ReconcilesSession {
        private val mutableState = MutableStateFlow(initial)

        @Volatile
        private var isDetached = false
        override val ref get() = this@ClaudeSession.ref
        override val route get() = this@ClaudeSession.route
        override val state = mutableState.asStateFlow()
        override val features = ClaudeFeatures(
            SendsPrompts to this,
            SessionHistory to history,
            ReconcilesSession to this,
        )
        override suspend fun send(request: PromptRequest): TurnId = this@ClaudeSession.send(request, this)
        override suspend fun synchronize() = reconcile(this)

        override suspend fun close() = synchronized(lock) {
            log.i { "Detaching Claude session handle" }
            isDetached = true
            leases.remove(this)
            mutableState.value = ActiveSessionState.Closed
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
    if (text.isBlank() || text.length > MAX_PROMPT_CHARS) {
        throw EngineException(EngineFailure.Request(RequestFailureReason.Invalid, request.id))
    }
    return text
}

private const val MAX_PROMPT_CHARS = 1024 * 1024

private data class Submission(val request: PromptRequest, val text: String, val turn: Turn, val previous: Turn?) {
    override fun toString(): String = "Submission(***)"
}

/** Dependencies shared by every session of one profile-owned runtime. */
internal data class ClaudeSessionEnvironment(
    val transport: ClaudeTransport,
    val account: ClaudeAccount,
    val toggles: FeatureToggles,
    val scope: CoroutineScope,
)
