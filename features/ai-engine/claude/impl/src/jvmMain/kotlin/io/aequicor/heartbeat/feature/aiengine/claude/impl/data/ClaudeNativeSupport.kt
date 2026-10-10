package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.NativeCallClassifier
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
internal class DesktopClaudeNativeSupport(
    private val workspaces: LocalWorkspaces,
    private val classifier: NativeCallClassifier,
) : ClaudeNativeSupport {
    override suspend fun open(workspace: WorkspaceRef): ClaudeNativeWorkspace? =
        workspaces.resolve(workspace)?.let { ClaudeNativeWorkspace(it, classifier) }
}

/** Classification never trusts a model-provided workspace or substitutes a different path for relative edits. */
internal class ClaudeNativeWorkspace(private val path: String, private val classifier: NativeCallClassifier) {
    suspend fun classify(name: String, input: JsonObject): NativeToolCall? {
        val spec = ClaudeNativeCatalog.singleOrNull { it.name == name && it.isGated } ?: return null
        val file = input.text("file_path") ?: input.text("path")
        val command = input.text("command")
        if (spec.action == AgentToolAction.Edit && file.isNullOrBlank()) return null
        if (name == "Bash" && command.isNullOrBlank()) return null
        val isHostTermination = name == "Bash" && classifier.terminatesHost(command.orEmpty())
        val isProjectEdit = spec.action == AgentToolAction.Edit && file != null && classifier.isWorkspaceEdit(
            file,
            path,
        )
        return NativeToolCall(
            name,
            spec.action,
            paths = listOfNotNull(file),
            command = command,
            arguments = input,
            covered = { trust ->
                !isHostTermination && (
                    isCovered(trust, spec.action, isProjectEdit)
                )
            },
        )
    }
}

/** 2.1.285 passed the real macOS CLI initialize, hook deny and ask/allow/deny protocol probe. */
internal fun supportsClaudeNativeGate(version: String?): Boolean {
    val parts = version?.takeIf { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) }
        ?.split('.')?.map { it.toIntOrNull() ?: return false } ?: return false
    return parts[0] > 2 || (parts[0] == 2 && (parts[1] > 1 || (parts[1] == 1 && parts[2] >= MINIMUM_PATCH)))
}

private fun isCovered(trust: TrustLevel, action: AgentToolAction, isProjectEdit: Boolean): Boolean =
    action == AgentToolAction.Read || trust == TrustLevel.Full || (trust == TrustLevel.AutoEdits && isProjectEdit)
private const val MINIMUM_PATCH = 285
