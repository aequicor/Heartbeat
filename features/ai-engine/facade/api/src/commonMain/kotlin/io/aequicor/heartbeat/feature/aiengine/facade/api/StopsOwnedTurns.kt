package io.aequicor.heartbeat.feature.aiengine.facade.api

/**
 * Explicit cancellation of an exact profile-owned request, including after the application restarts. Resolving
 * this capability does not execute anything. Calling it may start a metadata transport to check credentials,
 * but must never create, attach, resume, configure or prompt a native session, or alter the session index.
 *
 * Adapters validate the full session reference, stored credential/workspace route and request identity before
 * acting. They revoke and await termination of hosted work for this turn, and preserve a durable stop barrier
 * until native termination is proved. An outstanding hosted job also requires Unconfirmed.
 * A transport close, an empty history, a missing handle or a newly idle process does not establish termination.
 * Cancellation of the caller's wait propagates and leaves unresolved native work and its stop barrier retained.
 */
public interface StopsOwnedTurns : EngineFeature {
    /** Stops only [request] under [ref]; never acts on a newer request occupying the same session. */
    public suspend fun stop(ref: SessionRef, request: RequestId, access: OwnedTurnAccess): OwnedTurnStop

    /** Optional engine-wide cancellation capability; unsupported engines must not emulate it by resuming. */
    public companion object : EngineFeatureKey<StopsOwnedTurns>(
        EngineFeatureId("turn.stop_owned"),
        StopsOwnedTurns::class,
    )
}

/**
 * Exact engine/binding/workspace route and, when already known, the expected turn. Null workspace means no
 * project. The target's model does not reconfigure or filter the original turn: cancellation remains available
 * after model selection changes. A confirmed result retains the original turn's target/model.
 */
public data class OwnedTurnAccess(
    val target: EngineTarget,
    val workspace: WorkspaceRef? = null,
    val expectedTurn: TurnId? = null,
)

/** Whether this exact request is known to have ended; failure to find evidence never frees a helper's capacity. */
public sealed interface OwnedTurnStop {
    /**
     * Authoritative terminal result for the requested turn. Unknown is allowed only after confirmed termination
     * when its result cannot be recovered. A durable native terminal result may be returned without an OS stop.
     */
    public data class Confirmed(val turn: Turn) : OwnedTurnStop {
        init {
            requireNotNull(turn.request)
            requireNotNull(turn.outcome)
        }
    }

    /** Outstanding, mismatched or unavailable ownership/exit evidence; retain capacity and never resend R. */
    public data object Unconfirmed : OwnedTurnStop
}
