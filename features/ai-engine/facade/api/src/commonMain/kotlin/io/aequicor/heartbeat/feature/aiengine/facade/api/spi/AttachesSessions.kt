package io.aequicor.heartbeat.feature.aiengine.facade.api.spi

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef

/** Runtime capability used by the facade to implement the stored session's bound ResumesSessions capability. */
public interface AttachesSessions : EngineFeature {
    /**
     * Attaches the exact native session after checking runtime identity, source revision and effective auth.
     * Initializes state and observation atomically so no running turn or pending request is missed.
     * Rejects a foreign engine/store or incompatible route; never silently clones or switches credentials.
     */
    public suspend fun attach(ref: SessionRef, request: ResumeSessionRequest): ActiveSession

    /** Typed runtime attachment key. */
    public companion object : EngineFeatureKey<AttachesSessions>(
        EngineFeatureId("runtime.attach"),
        AttachesSessions::class,
    )
}
