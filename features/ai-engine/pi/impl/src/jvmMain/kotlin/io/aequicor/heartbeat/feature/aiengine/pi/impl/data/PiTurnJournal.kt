package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
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

/** Writes before prompt delivery and before publishing terminal state; late callbacks cannot replace a new turn. */
internal class PiTurnJournal(
    private val records: PiTurnRecords,
    private val ref: SessionRef,
    private val route: ExecutionRoute,
    private val ownership: String,
) {
    private val log = Log.tag("PiTurnJournal")

    suspend fun restore(): PiTurnSnapshot? = guarded { records.get(ref)?.also(::validate) }

    /** An unresolved stop forbids even opening a replacement native process. */
    suspend fun restoreForOpening(): PiTurnSnapshot? = restore()?.also {
        if (it.stopping != null) piFailure(EngineFailure.Session(SessionFailureReason.Busy))
    }

    suspend fun begin(turn: Turn, trust: TrustLevel, processOwner: PiExecutionOwner? = null) = guarded {
        records.update(ref) { previous ->
            val before = previous?.also(::validate) ?: PiTurnSnapshot(ref, route, ownership)
            check(
                before.active == null && before.stopping == null && before.last?.turn?.id != turn.id,
            ) { "Previous turn is unresolved" }
            before.copy(active = PiTurnRecord(turn, trust, processOwner))
        }
    }

    /** Only an irrevocably revoked submission whose preflight has exited can prove no prompt was sent. */
    suspend fun cancelBeforeSubmission(submission: PiSubmission): PiTurnRecord = guarded {
        check(submission.isStopRequested && submission.boundary.await() == PiSubmissionBoundary.NotSent)
        val turn = submission.turn
        val snapshot = records.update(ref) { previous ->
            val before = previous?.also(::validate) ?: PiTurnSnapshot(ref, route, ownership)
            check(before.stopping == null && (before.active == null || before.active.turn == turn))
            if (before.last?.turn?.id == turn.id) {
                check(before.last.turn.request == turn.request && before.last.turn.target == turn.target)
                before
            } else {
                before.copy(
                    active = null,
                    last = PiTurnRecord(turn.copy(outcome = TurnOutcome.Cancelled), submission.trust),
                )
            }
        }
        checkNotNull(snapshot.last)
    }

    suspend fun finish(turn: TurnId, outcome: TurnOutcome, answers: Map<String, TurnId?> = emptyMap()): PiTurnRecord? =
        guarded {
            val known = records.get(ref)?.also(::validate) ?: return@guarded null
            if (known.active?.turn?.id != turn) return@guarded known.last?.takeIf { it.turn.id == turn }
            val snapshot = records.update(ref) { previous ->
                val before = checkNotNull(previous).also(::validate)
                val active = before.active?.takeIf { it.turn.id == turn } ?: return@update before
                check(answers.values.all { it == null || it == turn })
                val merged = before.answerTurns.toMutableMap()
                answers.forEach { (key, observed) ->
                    merged[key] = if (key !in merged || merged[key] == observed) observed else null
                }
                before.copy(
                    active = null,
                    last = active.copy(turn = active.turn.copy(outcome = outcome)),
                    answerTurns = merged,
                )
            }
            snapshot.last?.takeIf { it.turn.id == turn }
        }

    /** Claims only this active request. No native IO can begin until this fence is durable. */
    suspend fun fenceStop(request: RequestId, turn: TurnId?): PiTurnRecord? = guarded {
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
    suspend fun inspectStop(stop: PiTurnRecord, inspection: String): Boolean = guarded {
        val snapshot = records.update(ref) { previous ->
            val before = checkNotNull(previous).also(::validate)
            if (before.stopping?.sameStop(stop) != true || before.stopInspection != null) return@update before
            before.copy(stopInspection = inspection)
        }
        snapshot.stopInspection == inspection
    }

    /** Newly discovered descendants must be retained before any signal can orphan them. */
    suspend fun observeStop(stop: PiTurnRecord, inspection: String, owner: PiExecutionOwner): PiExecutionOwner? =
        guarded {
            val snapshot = records.update(ref) { previous ->
                val before = checkNotNull(previous).also(::validate)
                val stopping = before.stopping?.takeIf { it.sameStop(stop) } ?: return@update before
                if (before.stopInspection != inspection) return@update before
                val current = checkNotNull(stopping.processOwner)
                check(current.launchId == owner.launchId && current.root == owner.root)
                val merged = current.copy(
                    observedChildren = (current.observedChildren + owner.observedChildren).distinct(),
                )
                before.copy(stopping = stopping.copy(processOwner = merged), stopInspection = null)
            }
            snapshot.stopping?.takeIf { snapshot.stopInspection == null && it.sameStop(stop) }?.processOwner
        }

    /** A stale waiter cannot clear a newer fence or omit descendants discovered by another waiter. */
    suspend fun stopped(stop: PiTurnRecord, observed: PiExecutionOwner): PiTurnRecord? = guarded {
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

internal fun PiTurnRecord.matches(request: RequestId, turn: TurnId?): Boolean =
    this.turn.request == request && (turn == null || this.turn.id == turn)

private fun PiTurnRecord.sameStop(other: PiTurnRecord): Boolean =
    turn.id == other.turn.id && turn.request == other.turn.request &&
        processOwner?.launchId == other.processOwner?.launchId && processOwner?.root == other.processOwner?.root
