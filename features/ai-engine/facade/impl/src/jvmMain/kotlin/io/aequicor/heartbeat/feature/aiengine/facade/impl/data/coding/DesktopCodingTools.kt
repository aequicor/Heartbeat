package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

/** Files and commands share the same implementation and trust semantics across local engines. */
@Inject
@ContributesIntoSet(ProfileScope::class)
internal class DesktopCodingTools(
    private val workspaces: LocalWorkspaces,
    private val dispatchers: DispatcherProvider,
) : AgentToolContribution {
    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> = tools(workspace).map {
        val action = when {
            it is CodingShellTool -> AgentToolAction.Command
            it.isMutating -> AgentToolAction.Edit
            else -> AgentToolAction.Read
        }
        it.descriptor.specification(action)
    }

    override suspend fun instructions(workspace: WorkspaceRef?): String = if (tools(workspace).isEmpty()) {
        ""
    } else {
        "Use the hosted project tools to read and edit the current workspace. " +
            "Read AGENTS.md and CLAUDE.md when present before changes. " +
            "Use run_command for foreground Git and other non-interactive commands. " +
            "It returns only after exit or termination on timeout; a returned result leaves no managed job to " +
            "wait for with scheduler_sleep. Handle the result and continue the task. " +
            "When configure_build/run_build are available, builds and tests MUST use run_build, not run_command. " +
            "File paths are confined to this workspace; direct .git edits are unavailable."
    }

    override fun approval(spec: AgentToolSpec, arguments: JsonObject): AgentToolApproval = AgentToolApproval(
        spec.name,
        when (spec.action) {
            AgentToolAction.Read -> spec.description
            AgentToolAction.Edit -> "Изменить файл ${arguments.argText("path")}"
            AgentToolAction.Command -> "Выполнить команду в проекте"
        },
        when (spec.action) {
            AgentToolAction.Read -> null

            AgentToolAction.Edit -> arguments.argText(
                "content",
            ).ifEmpty { arguments.argText("new_string") }.take(MAX_APPROVAL_CHARS)

            AgentToolAction.Command -> arguments.argText("command").also {
                require(it.length <= MAX_CODING_COMMAND_CHARS) { "Command is too long to review in full" }
            }
        },
    )

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult =
        tools(context.workspace).singleOrNull { it.descriptor.name == name }?.run(arguments)
            ?: AgentToolResult("Project tools are unavailable", isError = true)

    private suspend fun tools(workspace: WorkspaceRef?): List<CodingTool> {
        if (workspace == null || !workspaces.isAvailable) return emptyList()
        val directory = workspaces.resolve(workspace) ?: return emptyList()
        return withContext(dispatchers.io) {
            val root = ProjectRoot(Path.of(directory))
            codingFileTools(root, dispatchers.io) + CodingShellTool(root, dispatchers.io)
        }
    }
}

private const val MAX_APPROVAL_CHARS = 4_000
