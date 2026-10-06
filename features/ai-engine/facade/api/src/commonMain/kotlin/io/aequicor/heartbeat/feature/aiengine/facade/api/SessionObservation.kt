package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.coroutines.flow.Flow

/**
 * Read-only observation of profile-owned execution through a stored session. Observing never resumes a session,
 * acquires a handle, sends a prompt or changes its lifetime. Null means no open handle is known to the profile.
 */
public interface SessionObservation : EngineFeature {
    /** Follows handle replacement and closure for the exact native session identity. */
    public val snapshots: Flow<SessionObservationSnapshot?>

    /** Typed observation key. */
    public companion object : EngineFeatureKey<SessionObservation>(
        EngineFeatureId("session.observation"),
        SessionObservation::class,
    )
}

/** Current execution and optional native context telemetry of a borrowed profile handle. */
public data class SessionObservationSnapshot(
    val state: ActiveSessionState,
    val route: ExecutionRoute,
    val context: ContextUsage? = null,
)
