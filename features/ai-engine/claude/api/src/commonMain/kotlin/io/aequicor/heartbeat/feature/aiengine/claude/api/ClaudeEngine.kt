package io.aequicor.heartbeat.feature.aiengine.claude.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthContextKey
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthLocationId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/** Stable identities of the Claude Code CLI adapter. OAuth tokens remain owned by Claude Code. */
public object ClaudeEngine {
    public val Id: EngineId = EngineId("claude")
    public val AuthOwner: AuthOwnerId = AuthOwnerId("claude.code")
    public val AuthSource: AuthSourceId = AuthSourceId("claude.local")
    public val AuthLocation: AuthLocationId = AuthLocationId("claude.local")
    public val AuthContext: AuthContextKey = AuthContextKey("claude.code.anthropic")
    public val SessionSource: SessionSourceId = SessionSourceId("claude.local")
    public val Enabled: FeatureToggle.Flag = FeatureToggle.Flag(
        "ai.claude",
        "Движок Claude Code CLI",
        default = false,
    )
}

/**
 * Host configuration, injected by the bundle. Values are never executed through a shell or logged.
 * Null directories select the user's home and its .claude child. Workspace ids must be explicitly mapped.
 * Only the configured native CLI login is supported; keys, helpers and ambient provider overrides are rejected.
 * Use a native executable, not a Windows .cmd/.bat wrapper. Settings and MCP discovery are disabled for runs.
 */
public data class ClaudeConfiguration(
    public val executable: String = "claude",
    public val configDirectory: String? = null,
    public val workingDirectory: String? = null,
    public val workspaces: Map<WorkspaceRef, String> = emptyMap(),
) {
    init {
        require(executable.isNotBlank())
        require(configDirectory == null || configDirectory.isNotBlank())
        require(workingDirectory == null || workingDirectory.isNotBlank())
        require(workspaces.values.none(String::isBlank))
    }

    override fun toString(): String = "ClaudeConfiguration(***)"
}
