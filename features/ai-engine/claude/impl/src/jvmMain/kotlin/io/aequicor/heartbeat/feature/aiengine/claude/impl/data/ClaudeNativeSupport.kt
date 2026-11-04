package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.HOSTED_SEARCH_SERVER
import io.aequicor.heartbeat.feature.aiengine.facade.api.HOSTED_TOOLS_SERVER
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
            // A virtual workspace has no real directory: only workspace-free tools may run natively there.
            ?: workspaces.virtual(workspace)?.let { ClaudeNativeWorkspace(null) }
}

/** Classification never trusts a model-provided workspace or substitutes a different path for relative edits. */
internal class ClaudeNativeWorkspace(private val path: String?) {
    /** The gated tools this workspace can run: a virtual one has no directory, so only workspace-free tools qualify. */
    fun supported(gated: Set<String>): Set<String> =
        if (path == null) gated.filter { name -> specOf(name)?.isWorkspaceFree == true }.toSet() else gated

    fun classify(name: String, input: JsonObject): NativeToolCall? = when {
        isForeignClaudeMcp(name) -> path?.let { directory ->
            NativeToolCall(
                CLAUDE_MCP_GROUP,
                AgentToolAction.Command,
                command = name,
                arguments = input,
                workingDirectory = directory,
            )
        }

        else -> classifyBuiltin(name, input)
    }

    private fun classifyBuiltin(name: String, input: JsonObject): NativeToolCall? {
        val spec = specOf(name) ?: return null
        if (path == null && !spec.isWorkspaceFree) return null
        // A workspace-free tool reaches out from this device: only a public address may reach the profile gate.
        if (spec.isWorkspaceFree && !isPublicWebUrl(input.text("url").orEmpty())) return null
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
            isWorkspaceFree = spec.isWorkspaceFree,
        )
    }
}

private fun specOf(name: String) = ClaudeNativeCatalog.singleOrNull { it.name == name && it.isGated }

private const val MINIMUM_MAJOR = 2
private const val MINIMUM_MINOR = 1
private const val MINIMUM_PATCH = 285

/** 2.1.285 passed the real macOS CLI initialize, hook deny and ask/allow/deny protocol probe. */
internal fun supportsClaudeNativeGate(version: String?): Boolean {
    val parts = version?.takeIf { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) }
        ?.split('.')?.map { it.toIntOrNull() ?: return false } ?: return false
    if (parts[0] != MINIMUM_MAJOR) return parts[0] > MINIMUM_MAJOR
    return parts[1] > MINIMUM_MINOR || (parts[1] == MINIMUM_MINOR && parts[2] >= MINIMUM_PATCH)
}

/**
 * Lowest Claude Code version the adapter documents and relies on: [supportsClaudeNativeGate] accepts this build and
 * refuses older ones, and it is the version that passed the live macOS initialize/hook/permission protocol probe.
 * Admission uses it as the runtime floor instead of inventing a range.
 */
internal const val CLAUDE_NATIVE_GATE_FLOOR: String = "$MINIMUM_MAJOR.$MINIMUM_MINOR.$MINIMUM_PATCH"

private const val MAX_MCP_TOOL_NAME_LENGTH = 512

/** Heartbeat's exact MCP prefixes retain their own hosted/search authorization. */
internal fun isForeignClaudeMcp(name: String): Boolean = name.startsWith(
    "mcp__",
) && name.length <= MAX_MCP_TOOL_NAME_LENGTH &&
    !name.startsWith("mcp__${HOSTED_TOOLS_SERVER}__") &&
    !name.startsWith("mcp__${HOSTED_SEARCH_SERVER}__") &&
    name.removePrefix(
        "mcp__",
    ).let { it.substringBefore("__").isNotEmpty() && it.substringAfter("__", "").isNotEmpty() }
