package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.searchengine.api.isPublicWebUrl
import kotlinx.serialization.json.JsonObject

/** Captures the trusted project path before the process starts; unsupported adapters cannot enable native tools. */
internal fun interface ClaudeNativeSupport {
    suspend fun open(workspace: WorkspaceRef): ClaudeNativeWorkspace?
}

internal object MissingClaudeNativeSupport : ClaudeNativeSupport {
    override suspend fun open(workspace: WorkspaceRef): ClaudeNativeWorkspace? = null
}

@ContributesBinding(ProfileScope::class)
@Inject
internal class DesktopClaudeNativeSupport(private val workspaces: LocalWorkspaces) : ClaudeNativeSupport {
    override suspend fun open(workspace: WorkspaceRef): ClaudeNativeWorkspace? =
        workspaces.resolve(workspace)?.let { ClaudeNativeWorkspace(it) }
            // A virtual workspace has no real directory: only the path-free web tools may run natively there.
            ?: workspaces.virtual(workspace)?.let { ClaudeNativeWorkspace(null) }
}

/** Classification never trusts a model-provided workspace or substitutes a different path for relative edits. */
internal class ClaudeNativeWorkspace(private val path: String?) {
    /** The gated tools this workspace can run: a virtual one has no directory, so only web reads qualify. */
    fun supported(gated: Set<String>): Set<String> = if (path == null) gated.intersect(WEB_TOOLS) else gated

    fun classify(name: String, input: JsonObject): NativeToolCall? {
        val spec = ClaudeNativeCatalog.singleOrNull { it.name == name && it.isGated } ?: return null
        if (path == null && name !in WEB_TOOLS) return null
        // The CLI fetches from this device: a local or private address must never reach the profile gate.
        if (name == "WebFetch" && !isPublicWebUrl(input.text("url").orEmpty())) return null
        val file = input.text("file_path") ?: input.text("path")
        val command = input.text("command")
        if (spec.action == AgentToolAction.Edit && file.isNullOrBlank()) return null
        if (name == "Bash" && command.isNullOrBlank()) return null
        return NativeToolCall(
            name,
            spec.action,
            paths = listOfNotNull(file),
            command = command,
            arguments = input,
            workingDirectory = path,

        )
    }
}

private val WEB_TOOLS = setOf("WebFetch")

/** 2.1.285 passed the real macOS CLI initialize, hook deny and ask/allow/deny protocol probe. */
internal fun supportsClaudeNativeGate(version: String?): Boolean {
    val parts = version?.takeIf { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) }
        ?.split('.')?.map { it.toIntOrNull() ?: return false } ?: return false
    return parts[0] > 2 || (parts[0] == 2 && (parts[1] > 1 || (parts[1] == 1 && parts[2] >= MINIMUM_PATCH)))
}

private const val MINIMUM_PATCH = 285
