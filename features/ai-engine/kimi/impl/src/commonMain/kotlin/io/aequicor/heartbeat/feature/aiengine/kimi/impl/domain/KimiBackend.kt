package io.aequicor.heartbeat.feature.aiengine.kimi.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineFactory
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineManager

/** Platform bridge for Kimi sessions and read-only inspection of the installed system CLI. */
public interface KimiBackend :
    EngineFactory,
    EngineManager {
    /** Looks up a session observed by this profile without discovering external history. */
    public suspend fun session(ref: SessionRef): EngineSession
}
