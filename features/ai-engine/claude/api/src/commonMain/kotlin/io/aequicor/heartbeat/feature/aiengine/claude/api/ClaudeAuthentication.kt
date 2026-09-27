package io.aequicor.heartbeat.feature.aiengine.claude.api

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthCheck
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource

/** Explicit read-only CLI authentication probe. Never logs in, launches a helper or imports OAuth material. */
public interface ClaudeAuthentication {
    /** Probes the configured CLI login; failures are EngineException and cancellation is propagated. */
    public suspend fun inspect(): ClaudeLogin
}

/** Non-secret CLI source metadata and its current observation, suitable for an explicit facade binding. */
public data class ClaudeLogin(val source: AuthSource.CliLogin, val check: AuthCheck)
