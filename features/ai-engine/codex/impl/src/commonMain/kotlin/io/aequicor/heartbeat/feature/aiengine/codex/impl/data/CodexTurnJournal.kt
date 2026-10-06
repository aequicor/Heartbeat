package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
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

    suspend fun begin(turn: Turn, trust: TrustLevel, processOwner: CodexExecutionOwner? = null) = guarded {
        checkNotNull(ownership) { "Native store ownership unavailable" }
        records.update(ref) { previous ->
            val before = previous?.also(::validate) ?: CodexTurnSnapshot(ref, route, ownership)
            check(
                before.active == null && before.stopping == null && before.last?.turn?.id != turn.id,
            ) { "Previous turn is unresolved" }
            before.copy(active = CodexTurnRecord(turn, null, trust, processOwner))
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

    /** Claims only this active request. No native IO can begin until this fence is durable. */
    suspend fun fenceStop(request: RequestId, turn: TurnId?): CodexTurnRecord? = guarded {
        if (records.get(ref) == null) return@guarded null
        val snapshot = records.update(ref) { previous ->
            val before = checkNotNull(previous).also(::validate)
            if (before.stopping != null) return@update before
            val active = before.active?.takeIf {
                it.matches(request, turn) && it.processOwner != null
            } ?: return@update before
            before.copy(stopping = active)
        }
        snapshot.stopping?.takeIf { it.matches(request, turn) }
    }

    /** A pending inspection survives cancellation/crash and fails closed rather than forgetting unknown children. */
    suspend fun inspectStop(stop: CodexTurnRecord, inspection: String): Boolean = guarded {
        val snapshot = records.update(ref) { previous ->
            val before = checkNotNull(previous).also(::validate)
            if (before.stopping?.sameStop(stop) != true || before.stopInspection != null) return@update before
            before.copy(stopInspection = inspection)
        }
        snapshot.stopInspection == inspection
    }

    /** Newly discovered descendants must be retained before any signal can orphan them. */
    suspend fun observeStop(
        stop: CodexTurnRecord,
        inspection: String,
        owner: CodexExecutionOwner,
    ): CodexExecutionOwner? = guarded {
        val snapshot = records.update(ref) { previous ->
            val before = checkNotNull(previous).also(::validate)
            val stopping = before.stopping?.takeIf { it.sameStop(stop) } ?: return@update before
            if (before.stopInspection != inspection) return@update before
            val current = checkNotNull(stopping.processOwner)
            check(current.launchId == owner.launchId && current.root == owner.root)
            val merged = current.copy(observedChildren = (current.observedChildren + owner.observedChildren).distinct())
            before.copy(stopping = stopping.copy(processOwner = merged), stopInspection = null)
        }
        snapshot.stopping?.takeIf { snapshot.stopInspection == null && it.sameStop(stop) }?.processOwner
    }

    /** A stale waiter cannot clear a newer fence or omit descendants discovered by another waiter. */
    suspend fun stopped(stop: CodexTurnRecord, observed: CodexExecutionOwner): CodexTurnRecord? = guarded {
        val snapshot = records.update(ref) { previous ->
            val before = checkNotNull(previous).also(::validate)
            val stopping = before.stopping?.takeIf { it.sameStop(stop) } ?: return@update before
            if (before.stopInspection != null) return@update before
            val owner = checkNotNull(stopping.processOwner)
            check(owner.launchId == observed.launchId && owner.root == observed.root)
            if (!observed.observedChildren.containsAll(owner.observedChildren)) return@update before
            val record = listOfNotNull(before.active, before.last).single { it.sameStop(stop) }
            before.copy(
                active = null,
                last = record.copy(
                    turn = record.turn.copy(outcome = record.turn.outcome ?: TurnOutcome.Unknown),
                    processOwner = owner,
                    isProcessStopped = true,
                ),
                stopping = null,
            )
        }
        snapshot.last?.takeIf { snapshot.stopping == null && it.sameStop(stop) && it.isProcessStopped }
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

internal fun CodexTurnRecord.matches(request: RequestId, turn: TurnId?): Boolean =
    this.turn.request == request && (turn == null || this.turn.id == turn)

private fun CodexTurnRecord.sameStop(other: CodexTurnRecord): Boolean =
    turn.id == other.turn.id && turn.request == other.turn.request &&
        processOwner?.launchId == other.processOwner?.launchId && processOwner?.root == other.processOwner?.root
