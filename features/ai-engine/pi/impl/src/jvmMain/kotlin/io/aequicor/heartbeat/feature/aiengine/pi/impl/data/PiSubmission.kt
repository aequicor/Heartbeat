package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * One main-confined submission, installed before preflight can suspend. A stop is irreversible for this entry;
 * releasing the caller's acceptance wait does not discard its pre-native boundary or allow a delayed send.
 * The boundary describes possible native execution, independently of the potentially lost acceptance reply.
 */
internal class PiSubmission(
    val turn: Turn,
    private val revoke: (TurnId) -> Unit = {},
    val trust: TrustLevel = TrustLevel.Ask,
) {
    val accepted = CompletableDeferred<TurnId>()
    private val prepared = CompletableDeferred<PiSubmissionBoundary>()
    val boundary: Deferred<PiSubmissionBoundary> get() = prepared
    var isStopRequested: Boolean = false
        private set
    var isHandedOff: Boolean = false

    /** Synchronous admission revocation; caller cancellation cannot undo this marker. */
    fun stop() {
        isStopRequested = true
        revoke(turn.id)
        accepted.completeExceptionally(
            EngineException(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, turn.request)),
        )
    }

    fun ensureAllowed() {
        if (isStopRequested) piFailure(EngineFailure.Request(RequestFailureReason.Invalid, turn.request))
    }

    /** No suspension may separate this final admission check from starting the RPC on [origin]. */
    fun nativeMayStart(origin: PiConnection) {
        ensureAllowed()
        check(prepared.complete(PiSubmissionBoundary.NativeMayStart(origin)))
    }

    /** Before handoff the sender owns this call; afterwards the profile-owned submission effect owns it. */
    fun settled() {
        prepared.complete(PiSubmissionBoundary.NotSent)
    }
}

internal sealed interface PiSubmissionBoundary {
    /** Preparation ended without crossing the final native admission check. */
    data object NotSent : PiSubmissionBoundary

    /** The captured connection may have received the prompt; acceptance is deliberately not required. */
    data class NativeMayStart(val origin: PiConnection) : PiSubmissionBoundary
}
