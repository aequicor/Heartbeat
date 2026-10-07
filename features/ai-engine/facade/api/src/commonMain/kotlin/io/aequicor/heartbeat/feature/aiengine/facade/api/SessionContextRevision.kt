package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.coroutines.flow.StateFlow

/** Native retained-context identity, independent of usage telemetry and its visibility toggle. */
public interface SessionContextRevision : EngineFeature {
    /**
     * Opaque identity shared by handles of the same native context. Changes after compaction or process replacement;
     * null means retention is uncertain (including an ongoing compaction), so delivery cannot be deduplicated.
     * Reading and collecting never opens a session or sends a request. Values are not persisted native identifiers.
     */
    public val state: StateFlow<String?>

    /** Typed retained-context key. */
    public companion object : EngineFeatureKey<SessionContextRevision>(
        EngineFeatureId("session.context_revision"),
        SessionContextRevision::class,
    )
}
