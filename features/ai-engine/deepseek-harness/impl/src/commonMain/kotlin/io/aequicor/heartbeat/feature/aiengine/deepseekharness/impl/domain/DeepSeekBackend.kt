package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.domain

import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekAuthentication
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineFactory

/** Platform bridge for DeepSeek Harness sessions; authentication is read-only from the user's DSH login. */
public interface DeepSeekBackend :
    EngineFactory,
    DeepSeekAuthentication {
    /** Looks up a session observed by this profile without discovering external history. */
    public suspend fun session(ref: SessionRef): EngineSession

    /** Native session discovery from the DSH on-disk store; null reports Unsupported coverage. */
    public val nativeDiscovery: ListsSessions? get() = null
}
