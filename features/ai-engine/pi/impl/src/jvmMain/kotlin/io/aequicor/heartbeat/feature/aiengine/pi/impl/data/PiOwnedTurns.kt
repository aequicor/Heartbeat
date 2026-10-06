package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnStop
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.StopsOwnedTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Engine-wide exact cancellation. Runtime administration never stays locked across preparation, drain or OS IO. */
internal class PiOwnedTurns(
    private val runtime: PiRuntime,
    private val processes: PiProcesses,
    private val sessions: Set<PiSession>,
    private val attaching: Set<SessionRef>,
    private val commands: Mutex,
) : StopsOwnedTurns {
    private val log = Log.tag("PiOwnedTurns")
    private val reservations = ConcurrentHashMap<SessionRef, Reservation>()

    fun isReserved(nativeId: String?): Boolean = reservations.keys.any { it.nativeId == nativeId }

    override suspend fun stop(ref: SessionRef, request: RequestId, access: OwnedTurnAccess): OwnedTurnStop =
        withContext(runtime.dispatchers.main) {
            runtime.ensureOpen()
            if (ref.engine != runtime.identity.engine || ref.source != PiSessionSource ||
                access.target.engine != ref.engine
            ) {
                piFailure(EngineFailure.Session(SessionFailureReason.NotFound))
            }
            val route = ExecutionRoute(
                ref.engine,
                access.target.binding,
                runtime.identity.source,
                runtime.identity.revision,
                access.workspace,
            )
            runtime.validateStop(access)
            val reservation = commands.withLock { reserve(ref, request, route, access) }
                ?: return@withContext OwnedTurnStop.Unconfirmed
            reservation.mutex.withLock { perform(ref, request, access, reservation) }
        }

    private suspend fun perform(
        ref: SessionRef,
        request: RequestId,
        access: OwnedTurnAccess,
        reservation: Reservation,
    ): OwnedTurnStop = try {
        runtime.validate()
        reservation.cached(access.expectedTurn) ?: resolve(ref, request, access, reservation)
    } finally {
        // Before validating cold ownership no work was revoked or signalled. A bad route must not poison retry.
        if (!reservation.isValidated && reservation.claim == null && reservations[ref] === reservation) {
            reservations.remove(ref)
        }
    }

    private fun reserve(
        ref: SessionRef,
        request: RequestId,
        route: ExecutionRoute,
        access: OwnedTurnAccess,
    ): Reservation? {
        reservations[ref]?.let { old ->
            return old.takeIf {
                it.request == request && it.route == route &&
                    (access.expectedTurn == null || it.claim?.turn?.id == access.expectedTurn || it.claim == null)
            }
        }
        return if (ref in attaching) {
            null
        } else {
            val session = sessions.firstOrNull { it.attachedRef == ref }
            if (session != null && session.route != route) {
                piFailure(
                    EngineFailure.Session(SessionFailureReason.Changed),
                )
            }
            val claim = session?.stopping?.begin(request, access.expectedTurn)
            Reservation(request, route, session, claim).also { reservations[ref] = it }
        }
    }

    private suspend fun resolve(
        ref: SessionRef,
        request: RequestId,
        access: OwnedTurnAccess,
        reservation: Reservation,
    ): OwnedTurnStop {
        val journal = PiTurnJournal(runtime.environment.turns, ref, reservation.route, runtime.credential)
        val snapshot = journal.restore()
        val selected = select(snapshot, request, access, reservation)
        if (selected == null) {
            if (reservations[ref] === reservation) reservations.remove(ref)
            return OwnedTurnStop.Unconfirmed
        }
        reservation.isValidated = true
        val result = stopSelected(ref, journal, selected, reservation)
        return if (result == null) OwnedTurnStop.Unconfirmed else complete(ref, reservation, result)
    }

    private fun select(
        snapshot: PiTurnSnapshot?,
        request: RequestId,
        access: OwnedTurnAccess,
        reservation: Reservation,
    ): Turn? {
        reservation.claim?.let { return it.turn }
        // Without a live claim, only a historical terminal is safe while another live request owns the session.
        val candidates = if (reservation.session == null) {
            listOfNotNull(snapshot?.active, snapshot?.last)
        } else {
            listOfNotNull(snapshot?.last?.takeIf { snapshot.stopping == null })
        }
        return candidates.firstOrNull { it.matches(request, access.expectedTurn) }?.turn
    }

    private suspend fun stopSelected(
        ref: SessionRef,
        journal: PiTurnJournal,
        turn: Turn,
        reservation: Reservation,
    ): PiTurnRecord? {
        val session = reservation.session
        val claim = reservation.claim
        val isPrepared = session == null || claim == null || session.stopping.prepare(claim)
        val isDrained = isPrepared && (session == null || session.hostedJobs.drain(turn.id)) &&
            runtime.environment.hostedDrains.drain(ref, reservation.route, runtime.credential, turn.id)
        if (!isDrained) return null
        val pending = claim?.submission
        return if (pending != null && claim.boundary == PiSubmissionBoundary.NotSent) {
            journal.cancelBeforeSubmission(pending)
        } else {
            PiStoredTurnStop(journal, processes).stop(checkNotNull(turn.request), turn.id)
        }
    }

    private suspend fun complete(
        ref: SessionRef,
        reservation: Reservation,
        result: PiTurnRecord,
    ): OwnedTurnStop.Confirmed {
        val session = reservation.session
        val claim = reservation.claim
        runtime.ensureOpen()
        return withContext(NonCancellable) {
            if (session != null && claim != null) session.stopping.complete(claim, result)
            val confirmed = OwnedTurnStop.Confirmed(result.turn)
            reservation.result = confirmed
            if (reservations[ref] === reservation) reservations.remove(ref)
            log.i { "Pi owned turn stop confirmed" }
            confirmed
        }
    }

    private class Reservation(
        val request: RequestId,
        val route: ExecutionRoute,
        val session: PiSession?,
        val claim: PiStopClaim?,
    ) {
        val mutex = Mutex()
        var result: OwnedTurnStop.Confirmed? = null
        var isValidated = false

        fun cached(expected: TurnId?): OwnedTurnStop? = result?.let {
            if (expected == null || it.turn.id == expected) it else OwnedTurnStop.Unconfirmed
        }
    }
}
