package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
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

/** Durable native submission boundary. Late acceptance may fill terminal correlation but never revive it. */
internal class CodexTurnJournal(
    private val records: CodexTurnRecords,
    private val ref: SessionRef,
    private val route: ExecutionRoute,
    private val ownership: String?,
) {
    private val log = Log.tag("CodexTurnJournal")

    suspend fun restore(): CodexTurnSnapshot? = guarded {
        records.get(ref)?.also {
            checkNotNull(ownership) { "Native store ownership unavailable" }
            validate(it)
        }
    }

    suspend fun begin(turn: Turn, trust: TrustLevel) = guarded {
        checkNotNull(ownership) { "Native store ownership unavailable" }
        records.update(ref) { previous ->
            val before = previous?.also(::validate) ?: CodexTurnSnapshot(ref, route, ownership)
            check(before.active == null && before.last?.turn?.id != turn.id) { "Previous turn is unresolved" }
            before.copy(active = CodexTurnRecord(turn, null, trust))
        }
    }

    suspend fun bind(turn: TurnId, native: String) = guarded {
        records.update(ref) { previous ->
            val before = checkNotNull(previous).also(::validate)
            when (turn) {
                before.active?.turn?.id -> before.copy(active = before.active.bind(native))

                before.last?.turn?.id -> before.copy(last = before.last.bind(native))

                // The response can arrive after this turn and its successor both completed.
                else -> before
            }
        }
    }

    suspend fun finish(turn: TurnId, outcome: TurnOutcome) = guarded {
        // Policy rejection may happen before begin; it has no native execution to retain.
        val known = records.get(ref)?.also(::validate) ?: return@guarded
        if (known.active?.turn?.id != turn) return@guarded
        records.update(ref) { previous ->
            val before = checkNotNull(previous).also(::validate)
            val active = before.active?.takeIf { it.turn.id == turn } ?: return@update before
            before.copy(active = null, last = active.copy(turn = active.turn.copy(outcome = outcome)))
        }
    }

    private fun validate(snapshot: CodexTurnSnapshot) {
        check(snapshot.ref == ref && snapshot.route == route && snapshot.ownership == ownership) {
            "Codex turn ownership changed"
        }
    }

    private fun CodexTurnRecord.bind(native: String): CodexTurnRecord {
        check(nativeId == null || nativeId == native) { "Codex native turn identity changed" }
        return copy(nativeId = native)
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: EngineException) {
        throw error
    } catch (error: Exception) {
        // Serialization/storage errors can include native identifiers; preserve only their type in diagnostics.
        log.w(IllegalStateException("Turn journal failed (${error::class.simpleName.orEmpty()})")) {
            "Codex turn journal unavailable"
        }
        fail(EngineFailure.Session(SessionFailureReason.NotResumable))
    }
}
