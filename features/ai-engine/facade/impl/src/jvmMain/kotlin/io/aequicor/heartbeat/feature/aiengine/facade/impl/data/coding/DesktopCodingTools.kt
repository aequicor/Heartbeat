package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReviewer
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAutomaticAuthorization
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCatalogEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

/** Files and commands share host-bound interactive or independent automatic decisions across local engines. */
@Inject
@ContributesIntoSet(ProfileScope::class)
internal class DesktopCodingTools(
    private val workspaces: LocalWorkspaces,
    private val dispatchers: DispatcherProvider,
    reviewer: AgentSafetyReviewer,
) : AgentToolContribution {
    private val safety = DesktopCodingSafety(workspaces, dispatchers, reviewer)
    private val mutations = Mutex()
    override val group: String = "coding"
    override val title: String = "Работа с проектом"
    override val catalog: List<ToolCatalogEntry> = listOf(
        ToolCatalogEntry("read_file", AgentToolAction.Read),
        ToolCatalogEntry("list_dir", AgentToolAction.Read),
        ToolCatalogEntry("glob", AgentToolAction.Read),
        ToolCatalogEntry("grep", AgentToolAction.Read),
        ToolCatalogEntry("write_file", AgentToolAction.Edit),
        ToolCatalogEntry("edit_file", AgentToolAction.Edit),
        ToolCatalogEntry("run_command", AgentToolAction.Command),
    )

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> = tools(workspace).map {
        val action = when {
            it is CodingShellTool -> AgentToolAction.Command
            it.isMutating -> AgentToolAction.Edit
            else -> AgentToolAction.Read
        }
        it.descriptor.specification(action)
    }

    override suspend fun instructions(workspace: WorkspaceRef?): String = instructions(AgentToolScope(workspace))

    /** Guidance for the declared tools only: a disabled run_command removes its lines, not the project rules. */
    override suspend fun instructions(scope: AgentToolScope): String {
        val declared = tools(scope.workspace).map { it.descriptor.name }
            .filter { scope.declared?.contains(it) != false }
        if (declared.isEmpty()) return ""
        return buildList {
            add("Use the hosted project tools to read and edit the current workspace.")
            add("Read AGENTS.md and CLAUDE.md when present before changes.")
            if (SHELL_TOOL in declared) {
                add("Use run_command for foreground Git and other non-interactive commands.")
                add(
                    "It returns only after exit or termination on timeout; a returned result leaves no managed job " +
                        "to wait for with scheduler_sleep. Handle the result and continue the task.",
                )
                add(
                    "When configure_build/run_build are available, builds and tests MUST use run_build, " +
                        "not run_command.",
                )
            }
            add("File paths are confined to this workspace; direct .git edits are unavailable.")
        }.joinToString(" ")
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

    override suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval {
        val original = approval(spec, arguments)
        return if (context.isAutomaticMutation(spec.name)) {
            safety.snapshot(context, original, arguments)?.approval ?: original
        } else {
            original
        }
    }

    override suspend fun automaticAuthorization(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolAutomaticAuthorization? = if (context.isAutomaticMutation(spec.name)) {
        val snapshot = safety.snapshot(context, approval(spec, arguments), arguments)
        if (snapshot == null) {
            AgentToolAutomaticAuthorization.Denied("The project action cannot be captured safely")
        } else {
            safety.authorize(context, snapshot)
        }
    } else {
        null
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult =
        if (name in MUTATIONS) {
            mutations.withLock { executeMutation(context, name, arguments) }
        } else {
            tools(context.workspace).singleOrNull { it.descriptor.name == name }?.run(arguments) ?: unavailable()
        }

    /** Final snapshot and execution share the mutation slot, including writes from interactive callers. */
    private suspend fun executeMutation(
        context: AgentToolContext,
        name: String,
        arguments: JsonObject,
    ): AgentToolResult {
        val tool = tools(context.workspace).singleOrNull { it.descriptor.name == name }
        return when {
            tool == null -> unavailable()
            !context.isAutomaticMutation(name) -> tool.run(arguments)
            else -> executeReviewed(context, tool, arguments)
        }
    }

    private suspend fun executeReviewed(
        context: AgentToolContext,
        tool: CodingTool,
        arguments: JsonObject,
    ): AgentToolResult {
        val action = if (tool.descriptor.name == SHELL_TOOL) AgentToolAction.Command else AgentToolAction.Edit
        val original = approval(tool.descriptor.specification(action), arguments)
        val snapshot = safety.snapshot(context, original, arguments)
        return if (snapshot == null || !safety.consume(context, snapshot)) {
            AgentToolResult("The safety review expired or the project action changed; request it again", true)
        } else {
            currentCoroutineContext().ensureActive()
            val capturedTools = codingFileTools(snapshot.root, dispatchers.io) +
                CodingShellTool(snapshot.root, dispatchers.io)
            capturedTools.single { it.descriptor.name == tool.descriptor.name }.run(snapshot.arguments)
        }
    }

    private suspend fun tools(workspace: WorkspaceRef?): List<CodingTool> {
        if (workspace == null || !workspaces.isAvailable) return emptyList()
        val directory = workspaces.resolve(workspace) ?: return emptyList()
        return withContext(dispatchers.io) {
            val root = ProjectRoot(Path.of(directory))
            codingFileTools(root, dispatchers.io) + CodingShellTool(root, dispatchers.io)
        }
    }
}

private fun AgentToolContext.isAutomaticMutation(name: String): Boolean =
    decisionMode == AgentToolDecisionMode.AutomaticSafety && name in MUTATIONS

private fun unavailable() = AgentToolResult("Project tools are unavailable", isError = true)

private const val MAX_APPROVAL_CHARS = 4_000
private const val SHELL_TOOL = "run_command"
private val MUTATIONS = setOf("write_file", "edit_file", SHELL_TOOL)
