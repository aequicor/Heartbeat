package io.aequicor.heartbeat.feature.aiengine.deepseekharness.api

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthCheck
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource

/** Explicit read-only CLI authentication probe. Never logs in, launches a helper or imports credential material. */
public interface DeepSeekAuthentication {
    /** Probes the installed DeepSeek Harness login; failures are EngineException and cancellation is propagated. */
    public suspend fun inspect(): DeepSeekLogin
}

/** Non-secret CLI source metadata and its current observation, suitable for an explicit facade binding. */
public data class DeepSeekLogin(val source: AuthSource.CliLogin, val check: AuthCheck)
