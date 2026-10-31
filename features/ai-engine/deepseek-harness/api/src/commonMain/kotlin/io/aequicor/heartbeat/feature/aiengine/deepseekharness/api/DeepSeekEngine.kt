package io.aequicor.heartbeat.feature.aiengine.deepseekharness.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthContextKey
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthLocationId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/** Stable identities of the DeepSeek Harness ACP adapter. OAuth tokens remain owned by DeepSeek Harness. */
public object DeepSeekEngine {
    public val Id: EngineId = EngineId("deepseek-harness")
    public val AuthOwner: AuthOwnerId = AuthOwnerId("deepseek.harness")
    public val AuthSource: AuthSourceId = AuthSourceId("deepseek.local")
    public val AuthLocation: AuthLocationId = AuthLocationId("deepseek.local")
    public val AuthContext: AuthContextKey = AuthContextKey("deepseek.harness.account")
    public val SessionSource: SessionSourceId = SessionSourceId("deepseek.local")
    public val Enabled: FeatureToggle.Flag = FeatureToggle.Flag(
        "ai.deepseek_harness",
        "Движок DeepSeek Harness",
        default = true,
    )
}

/**
 * Host configuration. Sessions use the user-installed `dsh` CLI (never a Heartbeat-managed runtime) and the
 * mandatory Heartbeat permission gate, sharing the DeepSeek Harness desktop login. Values are never executed
 * through a shell or logged. A null working directory selects the user's home; a null home directory keeps the
 * CLI's own default (`~/.dsh`). Workspace ids are resolved through the profile's LocalWorkspaces registry.
 */
public data class DeepSeekConfiguration(
    public val executable: String = "dsh",
    public val homeDirectory: String? = null,
    public val workingDirectory: String? = null,
    public val workspaces: Map<WorkspaceRef, String> = emptyMap(),
) {
    init {
        require(executable.isNotBlank())
        require(homeDirectory == null || homeDirectory.isNotBlank())
        require(workingDirectory == null || workingDirectory.isNotBlank())
        require(workspaces.values.none(String::isBlank))
    }

    override fun toString(): String = "DeepSeekConfiguration(***)"
}
