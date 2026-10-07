package io.aequicor.heartbeat.feature.aiengine.facade.api

/** Adapter-owned receipts and fresh native inspection for persistent background assignments. */
public interface RestoresSessionTurns : EngineFeature {
    /** Opaque receipt bound to this session and request; callers must persist it before relying on recovery. */
    public suspend fun checkpoint(request: RequestId): String?

    /**
     * Queries native state even when the local handle is Ready. A matched running turn is reattached to the
     * active session. Missing ownership or incomplete snapshots return Unknown; no prompt is submitted.
     */
    public suspend fun inspect(checkpoint: String?): TurnInspection

    /** Typed persistent recovery capability. */
    public companion object : EngineFeatureKey<RestoresSessionTurns>(
        EngineFeatureId("session.restore_turns"),
        RestoresSessionTurns::class,
    )
}

/** Fresh evidence, separate from a cached Ready state and from inferred success. */
public sealed interface TurnInspection {
    /** Native session is idle; continue in its history, checking prior effects before repeating work. */
    public data object Idle : TurnInspection

    /** Native activity or its owner/outcome could not be established. */
    public data object Unknown : TurnInspection

    /** A receipt-matched turn; null outcome means it is still running and exposed by the active handle. */
    public data class Observed(val request: RequestId, val outcome: TurnOutcome?) : TurnInspection
}
