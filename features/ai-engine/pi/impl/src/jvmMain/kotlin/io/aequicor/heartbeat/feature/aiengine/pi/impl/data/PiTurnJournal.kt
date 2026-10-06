package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CancellationException

/** Writes before prompt delivery and before publishing terminal state; late callbacks cannot replace a new turn. */
internal class PiTurnJournal(
    private val records: PiTurnRecords,
    private val ref: SessionRef,
    private val route: ExecutionRoute,
    private val ownership: String,
) {
    private val log = Log.tag("PiTurnJournal")

    suspend fun restore(): PiTurnSnapshot? = guarded { records.get(ref)?.also(::validate) }

    suspend fun begin(turn: Turn, trust: TrustLevel) = guarded {
        records.update(ref) { previous ->
            val before = previous?.also(::validate) ?: PiTurnSnapshot(ref, route, ownership)
            check(before.active == null && before.last?.turn?.id != turn.id) { "Previous turn is unresolved" }
            before.copy(active = PiTurnRecord(turn, trust))
        }
    }

    suspend fun finish(turn: TurnId, outcome: TurnOutcome): PiTurnRecord? = guarded {
        val known = records.get(ref)?.also(::validate) ?: return@guarded null
        if (known.active?.turn?.id != turn) return@guarded known.last?.takeIf { it.turn.id == turn }
        val snapshot = records.update(ref) { previous ->
            val before = checkNotNull(previous).also(::validate)
            val active = before.active?.takeIf { it.turn.id == turn } ?: return@update before
            before.copy(active = null, last = active.copy(turn = active.turn.copy(outcome = outcome)))
        }
        snapshot.last?.takeIf { it.turn.id == turn }
    }

    private fun validate(snapshot: PiTurnSnapshot) {
        check(snapshot.ref == ref && snapshot.route == route && snapshot.ownership == ownership) {
            "Pi turn ownership changed"
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: EngineException) {
        throw error
    } catch (error: Exception) {
        log.w(IllegalStateException("Turn journal failed (${error::class.simpleName.orEmpty()})")) {
            "Pi turn journal unavailable"
        }
        piFailure(EngineFailure.Session(SessionFailureReason.NotResumable))
    }
}

/** A new native process cannot attest that a previous process stopped, even if its transcript is idle. */
internal fun PiTurnSnapshot?.piInitialState(): ActiveSessionState = this?.active?.let {
    ActiveSessionState.Unavailable(EngineFailure.Session(SessionFailureReason.NotResumable), it.turn, last?.turn)
} ?: ActiveSessionState.Ready(this?.last?.turn)
