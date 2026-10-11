package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionEffect
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnStop
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    private val execute: suspend (ActiveSessionEffect) -> Unit,
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
     * exit, anything else rereads native state. A session still Unavailable afterwards reports its failure.
     */
    suspend fun synchronize() {
        runtime.ensureOpen()
        val state = session.machine.state.value as? ActiveSessionState.Unavailable ?: return
        log.i { "Codex session synchronization requested" }
        val active = state.activeTurn
        if (active != null && session.isActiveExecutionOwned && active.id !in nativeTurns.values) {
            settle(active)
        } else {
            execute(ActiveSessionEffect.Recheck(active?.id))
        }
        settling?.join()
        (session.machine.state.value as? ActiveSessionState.Unavailable)?.let { fail(it.failure) }
    }

    /** Explicit cancellation of an unresolved turn: it settles only through its owner's proven exit. */
    suspend fun cancel(turn: TurnId): Boolean {
        val state = session.machine.state.value as? ActiveSessionState.Unavailable ?: return false
        if (state.activeTurn?.id != turn) return false
        synchronize()
        return true
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

    private suspend fun stopOwner(active: Turn) {
        val result = runtime.ownedTurns.stop(
            session.ref,
            checkNotNull(active.request),
            OwnedTurnAccess(session.target, session.route.workspace, active.id),
        )
        if (result !is OwnedTurnStop.Confirmed) log.i { "Codex unobserved turn keeps waiting: owner exit unconfirmed" }
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
