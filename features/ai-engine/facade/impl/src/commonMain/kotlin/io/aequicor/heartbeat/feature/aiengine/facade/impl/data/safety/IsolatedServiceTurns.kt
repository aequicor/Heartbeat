package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ArchivesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.answerOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Slot accounting of one kind of isolated service turn; see [IsolatedTurnSlots]. */
internal interface ServiceTurnSlots {
    /** Waits in the bounded queue; false when no slot is available in time. */
    suspend fun acquire(): Boolean

    /** A dismissed turn keeps its slot while its session has not confirmed termination. */
    fun startFinishing()

    /** Releases a running slot. */
    fun releaseRunning()

    /** Releases a finishing slot; [isAbandoned] when termination was never confirmed. */
    fun releaseFinishing(isAbandoned: Boolean)
}

/** One service turn: its model, trusted request id, host text and bounds. Text is never logged. */
internal data class ServiceTurn(
    val target: EngineTarget,
    val request: RequestId,
    val text: String,
    val timeout: Duration,
    val maxAnswerChars: Int,
) {
    override fun toString(): String = "ServiceTurn(target=$target, request=$request)"
}

/** Result of [IsolatedServiceTurns.run]; [Answered.text] is null when the completed turn left no answer. */
internal sealed interface ServiceTurnOutcome {
    data class Answered(val text: String?) : ServiceTurnOutcome {
        override fun toString(): String = "Answered(hasText=${text != null})"
    }

    data class Refused(val reason: ServiceTurnRefusal) : ServiceTurnOutcome
}

/** Why a service turn produced no answer. */
internal enum class ServiceTurnRefusal {
    NoToolCatalog,
    Unsupported,
    Busy,
    Closing,
    Timeout,
    NotSendable,
    Incomplete,
    NoHistory,
}

/**
 * A fresh service session on the caller's model; no workspace or hosted tools, no inherited harnesses, native tools
 * off before the first prompt. Runs one prompt in it and dismisses it: cancel only requests interruption, the slot and
 * the restrictions stay until that exact turn terminates, but at most [settleGrace]: then the handle is closed and the
 * slot released, while the session keeps its tool restrictions until the profile closes. Provider exceptions
 * propagate to the owner, which must not log their messages.
 */
internal class IsolatedServiceTurns(
    private val host: ServiceTurnHost,
    private val slots: ServiceTurnSlots,
    private val scope: CoroutineScope,
    private val settleGrace: Duration,
    /** The owner's logger; its tag tells reviews from other service turns. */
    private val log: Log,
) {
    private val handoffs = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val facade get() = host.facade
    private val isolation get() = host.isolation

    suspend fun run(turn: ServiceTurn): ServiceTurnOutcome {
        val target = turn.target
        val descriptor = facade.value.engines.state.value.firstOrNull { it.descriptor.id == target.engine }?.descriptor
            ?: return ServiceTurnOutcome.Refused(ServiceTurnRefusal.NoToolCatalog)
        val creates = facade.value.engines.features(target.engine).resolve(CreatesSessions) as? FeatureAccess.Available
            ?: return ServiceTurnOutcome.Refused(ServiceTurnRefusal.Unsupported)
        if (!scope.isActive) return ServiceTurnOutcome.Refused(ServiceTurnRefusal.Closing)
        if (!slots.acquire()) return ServiceTurnOutcome.Refused(ServiceTurnRefusal.Busy)
        // The queue may have waited minutes: never open a service session for a closing profile.
        if (!scope.isActive) {
            slots.releaseRunning()
            return ServiceTurnOutcome.Refused(ServiceTurnRefusal.Closing)
        }
        var session: ActiveSession? = null
        var attempt: ServiceAttempt? = null
        try {
            val claimed = host.journal.begin(turn, descriptor.nativeTools.map { it.name })
            attempt = claimed
            return withTimeoutOrNull(turn.timeout) {
                val opened = withContext(NonCancellable) {
                    creates.feature.create(
                        CreateSessionRequest(target, areSessionHooksEnabled = true, execution = claimed.binding),
                    ).also {
                        session = it
                        isolation.restrict(it.ref, claimed.nativeTools)
                        host.journal.opened(claimed.binding, it.ref)
                    }
                }
                if (scope.isActive) {
                    answer(opened, turn, claimed)
                } else {
                    ServiceTurnOutcome.Refused(ServiceTurnRefusal.Closing)
                }
            } ?: ServiceTurnOutcome.Refused(ServiceTurnRefusal.Timeout)
        } finally {
            withContext(NonCancellable) {
                val opened = session
                val claimed = attempt
                if (opened != null && claimed != null) {
                    dismiss(opened, claimed)
                } else {
                    if (claimed != null) cleanup("retire") { host.retire(claimed) }
                    slots.releaseRunning()
                }
            }
        }
    }

    private suspend fun answer(
        session: ActiveSession,
        turn: ServiceTurn,
        attempt: ServiceAttempt,
    ): ServiceTurnOutcome {
        val sends = session.features.resolve(SendsPrompts) as? FeatureAccess.Available
            ?: return ServiceTurnOutcome.Refused(ServiceTurnRefusal.NotSendable)
        val prompt = host.journal.prepare(attempt.binding)
        val sent = handoffs.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                val accepted = sends.feature.send(prompt)
                host.journal.accepted(attempt.binding, accepted)
                Result.success(accepted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(IllegalStateException(e::class.simpleName.orEmpty())) { "Service send failed" }
                Result.failure(e)
            }
        }.await().getOrThrow()
        val ended = session.state.first {
            it is ActiveSessionState.Ready || it is ActiveSessionState.Unavailable ||
                it is ActiveSessionState.AwaitingUserAction || it is ActiveSessionState.Closing ||
                it == ActiveSessionState.Closed
        }
        if (ended !is ActiveSessionState.Ready || ended.lastTurn?.id != sent ||
            ended.lastTurn?.outcome != TurnOutcome.Completed
        ) {
            return ServiceTurnOutcome.Refused(ServiceTurnRefusal.Incomplete)
        }
        host.journal.settled(attempt.binding, checkNotNull(ended.lastTurn))
        return readAnswer(session, sent, turn.maxAnswerChars)
    }

    private suspend fun readAnswer(session: ActiveSession, sent: TurnId, maxAnswerChars: Int): ServiceTurnOutcome {
        val history = session.features.resolve(SessionHistory) as? FeatureAccess.Available
            ?: return ServiceTurnOutcome.Refused(ServiceTurnRefusal.NoHistory)
        val items = history.feature.page(HistoryPageRequest(limit = HISTORY_PAGE)).items
        return ServiceTurnOutcome.Answered(answerOf(items, sent, maxChars = maxAnswerChars))
    }

    private suspend fun dismiss(session: ActiveSession, attempt: ServiceAttempt) {
        val isSettled = cleanup("stop") { stop(session, attempt) }
        if (isSettled && finish(session)) {
            slots.releaseRunning()
            return
        }
        slots.startFinishing()
        // Start even if profile cancellation raced with the handoff, so finally always releases the handle.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var isClosed = false
            try {
                val isTerminal = awaitProof(session, attempt)
                isClosed = if (isTerminal) finish(session) else cleanup("close") { session.close() }
                val isAbandoned = !isTerminal || !isClosed
                if (isAbandoned) {
                    log.w { "Service turn abandoned: terminal=$isTerminal closed=$isClosed; restrictions retained" }
                }
                slots.releaseFinishing(isAbandoned)
                if (!isClosed) awaitCancellation()
            } finally {
                if (!isClosed && withContext(NonCancellable) { cleanup("close") { session.close() } }) {
                    log.i { "Abandoned service turn handle closed with the profile" }
                }
            }
        }
    }

    /** Retirement prevents late entry; only exact known native completion may release durable isolation. */
    private suspend fun stop(session: ActiveSession, attempt: ServiceAttempt) {
        host.retire(attempt)
        var current = host.journal.get(attempt.binding)
        if (current.session == null) current = host.journal.opened(attempt.binding, session.ref)
        if (current.stage == ServiceStage.Settled) return
        val observed = session.state.value.observed()?.takeIf {
            it.request == current.request && it.target == current.target
        }
        if (current.stage == ServiceStage.Sending && observed != null) {
            current = host.journal.accepted(attempt.binding, observed.id)
        }
        if (current.stage in setOf(ServiceStage.Opened, ServiceStage.Prepared)) {
            host.journal.settled(attempt.binding)
        } else {
            stopEntered(session, current)
        }
    }

    private suspend fun stopEntered(session: ActiveSession, attempt: ServiceAttempt) {
        if (session.state.value is ActiveSessionState.Unavailable && session.state.value.terminal(attempt) == null) {
            (session.features.resolve(ReconcilesSession) as? FeatureAccess.Available)?.feature?.synchronize()
        }
        val turn = when (val state = session.state.value) {
            is ActiveSessionState.Running -> state.turn

            is ActiveSessionState.AwaitingUserAction -> state.turn

            is ActiveSessionState.Ready, is ActiveSessionState.Submitting, is ActiveSessionState.Interrupting,
            is ActiveSessionState.Unavailable, is ActiveSessionState.Closing, ActiveSessionState.Closed,
            -> null
        }?.takeIf { it.request == attempt.request && it.target == attempt.target }
        if (turn != null) {
            (session.features.resolve(CancelsTurns) as? FeatureAccess.Available)?.feature?.cancel(turn.id)
        }
        val ended = session.state.first { it.terminal(attempt) != null }
        host.journal.settled(attempt.binding, checkNotNull(ended.terminal(attempt)))
    }

    private suspend fun awaitProof(session: ActiveSession, attempt: ServiceAttempt): Boolean = try {
        withTimeoutOrNull(settleGrace) {
            host.retire(attempt)
            val current = host.journal.get(attempt.binding)
            val ended = session.state.first { it.terminal(current) != null }
            host.journal.settled(attempt.binding, checkNotNull(ended.terminal(current)))
            true
        } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(IllegalStateException(e::class.simpleName.orEmpty())) { "Service terminal observation failed" }
        false
    }

    private suspend fun finish(session: ActiveSession): Boolean {
        cleanup("archive") {
            (facade.value.sessions.get(session.ref).features.resolve(ArchivesSessions) as? FeatureAccess.Available)
                ?.feature?.setArchived(true)
        }
        val isClosed = cleanup("close") { session.close() }
        if (isClosed) isolation.release(session.ref)
        return isClosed
    }

    /** Provider errors may contain source; every phase, including handle detachment, has a finite deadline. */
    private suspend fun cleanup(phase: String, block: suspend () -> Unit): Boolean = try {
        val isCompleted = withTimeoutOrNull(CLEANUP_TIMEOUT) {
            block()
            true
        } ?: false
        if (!isCompleted) log.w { "Service turn $phase timed out" }
        isCompleted
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(IllegalStateException(e::class.simpleName.orEmpty())) { "Service turn $phase failed" }
        false
    }
}

/** Exact observed identity, used only to preserve the original turn when native send lost its ACK. */
private fun ActiveSessionState.observed(): Turn? = when (this) {
    is ActiveSessionState.Submitting -> turn
    is ActiveSessionState.Running -> turn
    is ActiveSessionState.AwaitingUserAction -> turn
    is ActiveSessionState.Interrupting -> turn
    is ActiveSessionState.Unavailable -> activeTurn ?: lastTurn
    is ActiveSessionState.Ready -> lastTurn
    is ActiveSessionState.Closing, ActiveSessionState.Closed -> null
}

/** Closed/Unknown and another request never prove service termination. */
private fun ActiveSessionState.terminal(attempt: ServiceAttempt): Turn? {
    val terminal = when (this) {
        is ActiveSessionState.Ready -> lastTurn

        is ActiveSessionState.Unavailable -> lastTurn?.takeIf { activeTurn == null }

        is ActiveSessionState.Submitting, is ActiveSessionState.Running, is ActiveSessionState.AwaitingUserAction,
        is ActiveSessionState.Interrupting, is ActiveSessionState.Closing, ActiveSessionState.Closed,
        -> null
    }
    return terminal?.takeIf {
        it.request == attempt.request && it.target == attempt.target &&
            (attempt.turn == null || it.id == attempt.turn) && it.outcome != null && it.outcome != TurnOutcome.Unknown
    }
}

private const val HISTORY_PAGE = 50
private val CLEANUP_TIMEOUT = 10.seconds
