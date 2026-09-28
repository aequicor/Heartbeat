package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesTo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeAuthentication
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration

/** Profile entry point for AI adapters until the facade runtime consumes their registrations. */
@ContributesTo(ProfileScope::class)
public interface AiEngineAccess {
    /** Metadata and lazy factories; reading the set never launches the CLI. */
    public val engineRegistrations: Set<EngineRegistration>

    /** Explicit local account probe; OAuth credentials remain owned by Claude Code. */
    public val claudeAuthentication: ClaudeAuthentication
}
