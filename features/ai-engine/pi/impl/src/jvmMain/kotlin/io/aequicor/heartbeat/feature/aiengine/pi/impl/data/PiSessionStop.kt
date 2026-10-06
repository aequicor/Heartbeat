package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.withTimeoutOrNull

/** Exact, irreversible main-confined admission revocation, retained until a durable stop receipt is applied. */
internal class PiSessionStop(private val session: PiSession, private val apply: (PiStopClaim, PiTurnRecord) -> Unit) {
    var claim: PiStopClaim? = null
        private set

    fun begin(request: RequestId, expected: TurnId?): PiStopClaim? {
        claim?.let { return it.takeIf { old -> old.turn.matches(request, expected) } }
        val pending = session.submissions.pending?.takeIf { it.turn.matches(request, expected) }
        val selected = pending?.turn ?: session.turn?.takeIf { it.matches(request, expected) }
        return selected?.let { turn ->
            val stop = PiStopClaim(turn, session.connection, pending)
            claim = stop
            pending?.stop()
            session.hostedJobs.revoke(turn.id)
            stop
        }
    }

    fun blocks(turn: TurnId?): Boolean = turn != null && claim?.turn?.id == turn

    suspend fun prepare(stop: PiStopClaim): Boolean {
        check(claim === stop)
        if (!stop.prepare()) return false
        if (session.state.value != ActiveSessionState.Closed && session.turn?.id == stop.turn.id) {
            session.machine.send(
                ActiveSessionIntent.Internal.Failed(
                    stop.turn.id,
                    EngineFailure.Session(SessionFailureReason.NotResumable),
                ),
            )
        }
        return true
    }

    suspend fun complete(stop: PiStopClaim, record: PiTurnRecord) {
        check(claim === stop && record.turn.id == stop.turn.id && record.turn.request == stop.turn.request)
        val isCurrent = session.turn?.id == record.turn.id
        apply(stop, record)
        if (isCurrent && session.state.value != ActiveSessionState.Closed) {
            session.machine.send(
                ActiveSessionIntent.Internal.Failed(
                    record.turn.id,
                    EngineFailure.Session(SessionFailureReason.NotResumable),
                ),
            )
            session.machine.send(
                ActiveSessionIntent.Internal.Synchronized(
                    active = null,
                    completed = ActiveSessionIntent.Internal.Finished(
                        record.turn.id,
                        checkNotNull(record.turn.outcome),
                    ),
                ),
            )
        }
        claim = null
    }
}

internal class PiStopClaim(val turn: Turn, val origin: PiConnection?, val submission: PiSubmission?) {
    var boundary: PiSubmissionBoundary? = null
        private set

    suspend fun prepare(): Boolean {
        if (submission == null) return true
        boundary = withTimeoutOrNull(PREPARE_TIMEOUT) { submission.boundary.await() }
        return boundary != null
    }

    private companion object {
        const val PREPARE_TIMEOUT = 5_000L
    }
}

private fun Turn.matches(request: RequestId, expected: TurnId?): Boolean =
    this.request == request && (expected == null || id == expected)
