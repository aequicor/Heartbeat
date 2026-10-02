package io.aequicor.heartbeat.feature.aiengine.codex.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthLocationId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/** Stable identifiers for the local Codex app-server adapter. No property performs IO. */
public object CodexEngine {
    /** Engine registration identity. */
    public val Id: EngineId = EngineId("codex")

    /** CLI login ownership namespace; OAuth material remains owned by Codex. */
    public val AuthOwner: AuthOwnerId = AuthOwnerId("codex")

    /** Experimental integration gate, disabled by default. */
    public val Enabled: FeatureToggle.Flag = FeatureToggle.Flag("ai.codex", "Локальный Codex CLI")
}

/**
 * Trusted host configuration, supplied by the application bundle. Paths are never interpreted by a shell.
 * Only CLI-owned ChatGPT login is supported; no key import, credential helper or silent account switching is
 * performed. Signing in happens only when the user asks for it in engine management, through the CLI's own
 * app-server flow. Engine management may also replace the executable and home through the profile's launch
 * settings. Android/iOS report UnsupportedPlatform.
 *
 * [executable] names a native executable (not a .cmd/.bat wrapper on Windows). The default `codex` also discovers
 * standard macOS app-bundled and CLI installations, and the Windows desktop app's native CLI cache under
 * `%LOCALAPPDATA%/OpenAI/Codex/bin`, when absent from PATH. Explicit paths are never replaced.
 * Null [homeDirectory] selects
 * the CLI's normal home. [workspaces] resolves opaque application workspace ids to absolute local directories.
 * [source] and [location] identify exactly this installation within the current Heartbeat profile.
 */
public data class CodexLocalConfiguration(
    public val executable: String = "codex",
    public val homeDirectory: String? = null,
    public val source: AuthSourceId = AuthSourceId("codex.local"),
    public val location: AuthLocationId = AuthLocationId("codex.local"),
    public val historySource: SessionSourceId = SessionSourceId("codex.local"),
    public val workspaces: Map<WorkspaceRef, String> = emptyMap(),
) {
    init {
        require(executable.isNotBlank())
        require(homeDirectory == null || homeDirectory.isNotBlank())
        require(workspaces.values.all { it.isNotBlank() })
    }
    override fun toString(): String = "CodexLocalConfiguration(***)"
}
