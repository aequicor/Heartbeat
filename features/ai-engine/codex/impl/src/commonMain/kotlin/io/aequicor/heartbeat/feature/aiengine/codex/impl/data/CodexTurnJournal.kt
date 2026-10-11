package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
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

    suspend fun restore(): CodexTurnSnapshot? = guarded { records.get(ref)?.also(::validate) }

    /** The fenced turn's identity only; resuming its stop validates ownership again before any signal. */
    suspend fun pendingStop(): CodexTurnRecord? = guarded { records.get(ref)?.stopping }

    /**
     * [settled] is the session's locally terminal last turn: a record of it left active by a failed terminal
     * write is completed with that outcome instead of blocking every later request.
     */
    suspend fun begin(
        turn: Turn,
        trust: TrustLevel,
        processOwner: CodexExecutionOwner? = null,
        settled: Turn? = null,
    ) = guarded {
        val owner = checkNotNull(ownership) { "Native store ownership unavailable" }
        var isCompleted = false
        records.update(ref) { previous ->
            val known = previous?.let(::rebound)
            val before = known?.completing(settled) ?: CodexTurnSnapshot(ref, route, owner)
            isCompleted = known != null && before.last != known.last
            check(
                before.active == null && before.stopping == null && before.last?.turn?.id != turn.id,
            ) { "Previous turn is unresolved" }
            before.copy(active = CodexTurnRecord(turn, null, trust, processOwner))
        }
        if (isCompleted) log.i { "Codex turn record completed from the observed terminal outcome" }
    }

    /**
     * A revoked, finished preflight proves this request cannot execute later. Absence of a native id alone is
     * never sufficient. A failed write preserves the caller's admission marker; another active turn is untouched.
     */
    suspend fun cancelBeforeSubmission(submission: CodexSubmission, trust: TrustLevel): CodexTurnRecord = guarded {
        check(submission.isStopRequested && submission.boundary.await() == CodexSubmissionBoundary.NotSent)
        checkNotNull(ownership) { "Native store ownership unavailable" }
        val turn = submission.turn
        val snapshot = records.update(ref) { previous ->
            val before = previous?.let(::rebound) ?: CodexTurnSnapshot(ref, route, ownership)
            check(before.stopping == null) { "A process stop is still pending" }
            val active = before.active
            check(active == null || (active.turn == turn && active.nativeId == null)) {
                "Another native submission is unresolved"
            }
            if (before.last?.turn?.id == turn.id) {
                check(before.last.turn.request == turn.request && before.last.turn.target == turn.target)
                before
            } else {
                before.copy(
                    active = null,
                    last = CodexTurnRecord(turn.copy(outcome = TurnOutcome.Cancelled), null, trust),
                )
            }
        }
        checkNotNull(snapshot.last)
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

    /**
     * Marks discovery pending before enumerating children. Signals follow only [observeStop] of the same
     * inspection, so a marker left by an interrupted attempt signalled nothing and a newer inspection replaces it;
     * the superseded attempt can no longer record or signal.
     */
    suspend fun inspectStop(stop: CodexTurnRecord, inspection: String): Boolean = guarded {
        val snapshot = records.update(ref) { previous ->
            val before = checkNotNull(previous).also(::validate)
            if (before.stopping?.sameStop(stop) != true) return@update before
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

    /**
     * Unresolved work stays bound to the native store, account and route that began it. The credential revision is
     * not part of that ownership: a renewed CLI login must still be able to stop and settle its own turns.
     */
    private fun validate(snapshot: CodexTurnSnapshot) {
        check(snapshot.ref == ref) { "Codex turn ownership changed" }
        if (!snapshot.isUnresolved) return
        check(ownership != null && snapshot.ownership == ownership && snapshot.route.isSameOwner(route)) {
            "Codex turn ownership changed"
        }
    }

    /** History alone proves no running work, so it follows the current store, account and route. */
    private fun rebound(snapshot: CodexTurnSnapshot): CodexTurnSnapshot {
        validate(snapshot)
        return if (snapshot.isUnresolved) snapshot else snapshot.copy(route = route, ownership = ownership)
    }

    private fun CodexTurnSnapshot.completing(settled: Turn?): CodexTurnSnapshot {
        val outcome = settled?.outcome ?: return this
        val record = active?.takeIf { it.turn.id == settled.id && it.turn.request == settled.request }
        if (record == null || stopping != null) return this
        return copy(active = null, last = record.copy(turn = record.turn.copy(outcome = outcome)))
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

private val admissionLog = Log.tag("CodexTurnAdmission")

/** Nothing reached the native process yet: a refused begin is a proven rejection, never an unknown outcome. */
internal suspend fun CodexTurnJournal.admit(
    turn: Turn,
    trust: TrustLevel,
    processOwner: CodexExecutionOwner?,
    settled: Turn?,
) {
    try {
        begin(turn, trust, processOwner, settled)
    } catch (e: EngineException) {
        admissionLog.w(e) { "Codex submission refused: turn journal unavailable" }
        fail(EngineFailure.Request(RequestFailureReason.Invalid, turn.request))
    }
}

/** A rejected turn never started natively; a failed write is completed by the next begin from its outcome. */
internal suspend fun CodexTurnJournal.finishRejected(turn: TurnId, outcome: TurnOutcome) {
    try {
        finish(turn, outcome)
    } catch (e: EngineException) {
        admissionLog.w(e) { "Rejected Codex turn could not be recorded" }
    }
}

internal fun CodexTurnRecord.matches(request: RequestId, turn: TurnId?): Boolean =
    this.turn.request == request && (turn == null || this.turn.id == turn)

private fun ExecutionRoute.isSameOwner(other: ExecutionRoute): Boolean =
    copy(revision = AuthRevision.Unknown) == other.copy(revision = AuthRevision.Unknown)

private fun CodexTurnRecord.sameStop(other: CodexTurnRecord): Boolean =
    turn.id == other.turn.id && turn.request == other.turn.request &&
        processOwner?.launchId == other.processOwner?.launchId && processOwner?.root == other.processOwner?.root
