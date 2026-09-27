package io.aequicor.heartbeat.feature.aiengine.claude.impl.domain

import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeAuthentication
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineFactory

/** Platform bridge implemented only by the Claude adapter. */
public interface ClaudeBackend :
    EngineFactory,
    ClaudeAuthentication {
    /** Looks up an observed session without starting generation or discovering external history. */
    public suspend fun session(ref: SessionRef): EngineSession
}
