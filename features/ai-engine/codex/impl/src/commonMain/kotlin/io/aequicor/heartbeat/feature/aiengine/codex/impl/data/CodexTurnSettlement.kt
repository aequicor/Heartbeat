package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/**
 * Exit for a turn the session's process cannot observe: a restored one of an earlier execution process, or one
 * whose native id was never bound. A recorded owner is stopped, or proven gone, through the owned stop; a restored
 * turn without any recorded owner has none in this runtime and closes as Unknown. Otherwise the turn keeps waiting:
 * a turn that may still run is never closed without that evidence.
 */
internal class CodexTurnSettlement(
    private val session: CodexSession,
    private val journal: CodexTurnJournal,
    private val scope: ScopeHandle,
    private val nativeTurns: Map<String, TurnId>,
    private val rechecks: CodexRecheckAttempts,
    private val settleTerminal: suspend (Turn, TurnOutcome) -> Unit,
) {
    private val log = Log.tag("CodexTurnSettlement")
    private val runtime get() = session.runtime
    private var settling: Job? = null

    /** Never inline: callers may hold the runtime lock that the owned stop acquires. */
    fun settle(active: Turn) {
        if (settling?.isActive == true || scope.isClosed) return
        settling = scope.coroutineScope.launch { settleNow(active) }
    }

    /**
     * Explicit reconciliation of an Unavailable session: an unbound turn of this process settles through its owner's
     * exit, anything else rereads native state through the machine's Recheck. A session still Unavailable afterwards
     * reports its failure.
     *
     * Destructive for an unbound turn (for example after a turn/start timeout): its exit is proven by the owned stop,
     * which terminates the chat's whole execution process (app-server), together with any native subagents in it.
     */
    suspend fun synchronize() {
        runtime.ensureOpen()
        val state = session.machine.state.value as? ActiveSessionState.Unavailable ?: return
        log.v { "Codex session synchronization requested" }
        val active = state.activeTurn
        if (active != null && session.isActiveExecutionOwned && active.id !in nativeTurns.values) {
            settle(active)
        } else {
            recheck()
        }
        settling?.join()
        (session.machine.state.value as? ActiveSessionState.Unavailable)?.let { fail(it.failure) }
    }

    /**
     * Reconciliation through the machine: sends [ActiveSessionIntent.Public.Recheck] and waits for its attempt, so an
     * explicit request is one more machine recheck, never a reconcile that bypasses it. An attempt that started
     * earlier and ends meanwhile does not satisfy the wait. The caller reads the state.
     */
    private suspend fun recheck() {
        if (scope.isClosed) fail(EngineFailure.Session(SessionFailureReason.NotResumable))
        if (session.machine.send(ActiveSessionIntent.Public.Recheck) != SendResult.Accepted) {
            fail(EngineFailure.Session(SessionFailureReason.Changed))
        }
        // The machine launches the effect before send returns, so the latest started attempt is this one or later.
        rechecks.awaitFinished(rechecks.latest)
    }

    /**
     * Explicit cancellation of an unresolved turn: the machine accepts no Cancel while Unavailable, so the session is
     * synchronized first. True only when [turn] is no longer current afterwards; a turn that is running again is left
     * to the caller's machine Cancel, so its interrupt is still sent.
     */
    suspend fun cancel(turn: TurnId): Boolean {
        val state = session.machine.state.value as? ActiveSessionState.Unavailable ?: return false
        if (state.activeTurn?.id != turn) return false
        synchronize()
        return session.machine.state.value.codexCurrentTurn()?.id != turn
    }

    private suspend fun settleNow(active: Turn) {
        if (session.machine.state.value.codexCurrentTurn()?.id != active.id) return
        // A pending owned stop of this turn settles it; only the owner path may join (retry) that stop.
        val isClaimed = session.stopping.blocks(active.id) || runtime.ownedTurns.isReserved(session.ref.nativeId)
        try {
            val snapshot = journal.restore()
            val record = snapshot?.active?.takeIf { it.turn.id == active.id }
            val last = snapshot?.last?.takeIf { it.turn.id == active.id }
            when {
                record?.processOwner != null -> stopOwner(active)

                isClaimed -> log.i { "Codex unobserved turn waits for its pending owned stop" }

                record != null && !session.isActiveExecutionOwned -> closeOrphan(active)

                last != null -> {
                    session.hostedJobs.revoke(active.id)
                    settleTerminal(active, checkNotNull(last.turn.outcome))
                }

                else -> log.i { "Codex unobserved turn keeps waiting: its execution process is unknown" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "Codex unobserved turn could not be settled" }
        }
    }

    /** The owned stop reports its own result once, including why an unconfirmed exit keeps the turn waiting. */
    private suspend fun stopOwner(active: Turn) {
        runtime.ownedTurns.stop(
            session.ref,
            checkNotNull(active.request),
            OwnedTurnAccess(session.target, session.route.workspace, active.id),
        )
    }

    /** No execution process of this runtime ran the restored turn and no identity exists to stop another one. */
    private suspend fun closeOrphan(active: Turn) {
        val isDrained = runtime.host.hostedDrains.drain(
            session.ref,
            session.route,
            runtime.turnOwnership,
            active.id,
        ) && session.hostedJobs.drain(active.id)
        if (!isDrained) {
            log.i { "Codex restored turn keeps waiting for hosted cleanup" }
            return
        }
        journal.finish(active.id, TurnOutcome.Unknown)
        log.i { "Codex restored turn closed: no recorded execution process owns it" }
        settleTerminal(active, TurnOutcome.Unknown)
    }
}

/**
 * Numbered machine-driven reconciliation attempts, which explicit synchronization awaits by number: a waiter is never
 * woken by an attempt that started before its own.
 */
internal class CodexRecheckAttempts(scope: ScopeHandle) {
    private val log = Log.tag("CodexRecheckAttempts")
    private val startedAttempts = MutableStateFlow(0L)

    /** The highest finished attempt number; [Long.MAX_VALUE] once the session is closed. */
    private val finishedAttempt = MutableStateFlow(0L)

    init {
        // A closed session runs no further attempt; its waiters must not hang.
        scope.onClose {
            finishedAttempt.value = Long.MAX_VALUE
            log.v { "Codex recheck attempts closed: waiters released" }
        }
    }

    /** The number of the latest started attempt. */
    val latest: Long get() = startedAttempts.value

    /** Called when the machine launches a Recheck effect; returns the attempt's number. */
    fun started(): Long = startedAttempts.updateAndGet { it + 1 }.also { attempt ->
        log.v { "Codex recheck attempt started attempt=$attempt" }
    }

    /** Called after the Recheck effect [attempt] ends, successfully or not, including one cancelled before it ran. */
    fun finished(attempt: Long) {
        log.v { "Codex recheck attempt finished attempt=$attempt" }
        finishedAttempt.update { maxOf(it, attempt) }
    }

    /** Waits until an attempt numbered [attempt] or later has finished. */
    suspend fun awaitFinished(attempt: Long) {
        finishedAttempt.first { it >= attempt }
    }
}
