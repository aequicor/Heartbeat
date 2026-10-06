package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.withTimeoutOrNull

/** Retains live leases and exact admission identity while process termination or hosted cleanup is unresolved. */
internal class CodexSessionStop(
    private val session: CodexSession,
    private val settle: (CodexTurnRecord) -> Boolean,
    private val releaseSubmission: (CodexSubmission) -> Unit,
) {
    var claim: CodexStopClaim? = null
        private set

    /** Main-confined, with no suspension between matching the request and revoking its admission. */
    fun begin(request: RequestId, expected: TurnId?): CodexStopClaim? {
        claim?.let { return it.takeIf { old -> old.turn.matches(request, expected) } }
        val state = session.machine.state.value
        val pending = session.pendingSubmission?.takeIf { it.turn.matches(request, expected) }
        val turn = pending?.turn ?: state.codexCurrentTurn()?.takeIf { it.matches(request, expected) }
            ?: state.lastTurn()?.takeIf { it.matches(request, expected) }
        return turn?.let { selected ->
            val stop = CodexStopClaim(selected, session.connection, pending)
            claim = stop
            pending?.stop()
            session.hostedJobs.revoke(selected.id)
            stop
        }
    }

    fun owns(origin: CodexConnection): Boolean = claim?.let { it.origin === origin && it.turn.outcome == null } == true
    fun blocks(turn: TurnId?): Boolean = turn != null && claim?.turn?.id == turn

    /** Missing ACK is irrelevant; only preflight must reach its irrevocable native boundary. */
    suspend fun prepare(stop: CodexStopClaim): Boolean {
        check(claim === stop)
        if (!stop.prepare()) return false
        if (session.machine.state.value.codexCurrentTurn()?.id == stop.turn.id) {
            session.machine.send(ActiveSessionIntent.Internal.Failed(stop.turn.id, unavailable()))
        }
        return true
    }

    /** Called only after durable terminal evidence and hosted drain; unrelated current turns remain untouched. */
    suspend fun complete(stop: CodexStopClaim, record: CodexTurnRecord) {
        check(claim === stop && record.turn.id == stop.turn.id && record.turn.request == stop.turn.request)
        val isNative = stop.boundary is CodexSubmissionBoundary.NativeMayStart ||
            (stop.submission == null && stop.turn.outcome == null)
        if (isNative) session.runtime.discard(stop.origin)
        val isCurrent = session.machine.state.value.codexCurrentTurn()?.id == record.turn.id
        val isFirst = settle(record)
        if (isCurrent) {
            session.machine.send(ActiveSessionIntent.Internal.Failed(record.turn.id, unavailable()))
            session.machine.send(
                ActiveSessionIntent.Internal.Synchronized(
                    active = null,
                    completed = ActiveSessionIntent.Internal.Finished(
                        record.turn.id,
                        checkNotNull(record.turn.outcome),
                    ),
                ),
            )
            if (isFirst) {
                session.history.publish {
                    SessionEvent.TurnFinished(
                        it,
                        record.turn.id,
                        checkNotNull(record.turn.outcome),
                    )
                }
            }
        } else {
            val state = session.machine.state.value as? ActiveSessionState.Unavailable
            if (state?.activeTurn == null && state?.lastTurn?.id == record.turn.id) {
                session.machine.send(ActiveSessionIntent.Internal.Synchronized(active = null))
            }
        }
        stop.submission?.let(releaseSubmission)
        claim = null
    }

    private fun unavailable(): EngineFailure = EngineFailure.Session(SessionFailureReason.NotResumable)
}

internal class CodexStopClaim(val turn: Turn, val origin: CodexConnection, val submission: CodexSubmission?) {
    var boundary: CodexSubmissionBoundary? = null
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

private fun ActiveSessionState.lastTurn(): Turn? = when (this) {
    is ActiveSessionState.Ready -> lastTurn

    is ActiveSessionState.Unavailable -> lastTurn

    is ActiveSessionState.Submitting,
    is ActiveSessionState.Running,
    is ActiveSessionState.AwaitingUserAction,
    is ActiveSessionState.Interrupting,
    is ActiveSessionState.Closing,
    ActiveSessionState.Closed,
    -> null
}
