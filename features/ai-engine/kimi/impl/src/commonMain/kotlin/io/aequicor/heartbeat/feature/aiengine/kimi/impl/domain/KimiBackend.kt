package io.aequicor.heartbeat.feature.aiengine.kimi.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineFactory

/** Platform bridge implemented only by the Kimi Code adapter. */
public interface KimiBackend : EngineFactory {
    /** Looks up a session observed by this profile without discovering external history. */
    public suspend fun session(ref: SessionRef): EngineSession
}
