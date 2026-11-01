package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.Serializable

/**
 * Additional user input addressed to an already accepted turn. It never changes that turn's model, tool policy,
 * authority or lifecycle. An id correlates delivery; it does not promise native deduplication.
 */
@Serializable
public data class SteeringRequest(val id: RequestId, val parts: List<ContentPart>) {
    init {
        require(parts.isNotEmpty())
    }

    override fun toString(): String = "SteeringRequest(id=$id, parts=${parts.size})"
}

/** Optional native delivery into the current turn, at the engine's next supported execution boundary. */
public interface SteersTurns : EngineFeature {
    /**
     * Returns only after native acceptance into [turn]. A stale turn must be rejected, never replaced by a new
     * turn. Unknown delivery reports [RequestFailureReason.OutcomeUnknown] and must not be retried automatically.
     * This operation preserves pending approvals and the active turn's existing tool authority.
     */
    public suspend fun steer(turn: TurnId, request: SteeringRequest)

    /** Typed live steering capability. Engines without it can still receive ordinary queued turns. */
    public companion object : EngineFeatureKey<SteersTurns>(EngineFeatureId("session.steer"), SteersTurns::class)
}
