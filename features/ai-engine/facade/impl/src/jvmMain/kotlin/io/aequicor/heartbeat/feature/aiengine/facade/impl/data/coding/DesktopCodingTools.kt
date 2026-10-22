package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionEffect
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionResourceKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCatalogEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.security.ShellActionAnalyzer
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
) : AgentToolContribution {
    private val snapshots = DesktopCodingSnapshot(workspaces, dispatchers)
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
        ToolCatalogEntry("delete_file", AgentToolAction.Edit),
        ToolCatalogEntry("move_file", AgentToolAction.Edit),
    )

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> = tools(workspace).map {
        val action = when {
            it is CodingShellTool -> AgentToolAction.Command
            it.isMutating -> AgentToolAction.Edit
            else -> AgentToolAction.Read
        }
        it.descriptor.specification(action)
    }

    override suspend fun analyze(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): ActionAnalysis {
        val workspace = context.workspace ?: return ActionAnalysis.Unknown("A project is required")
        val virtual = workspaces.virtual(workspace)
        if (virtual != null) {
            return if (spec.name in VIRTUAL_READS) virtualReadAnalysis(virtual, spec.name, arguments) else {
                ActionAnalysis.Unknown("Virtual action needs confirmation")
            }
        }
        val directory = workspaces.resolve(workspace) ?: return ActionAnalysis.Unknown("Project is unavailable")
        val snapshot = if (spec.name in MUTATIONS) {
            snapshots.snapshot(context, approval(spec, arguments), arguments)
        } else {
            null
        }
        return withContext(dispatchers.io) {
            val root = ProjectRoot(Path.of(directory))
            if (spec.name == SHELL_TOOL) {
                val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
                ShellActionAnalyzer(dispatchers.io).analyze(
                    root,
                    root.path,
                    arguments.argText("command"),
                    controlledCodingShell(isWindows),
                )
            } else {
                codingFileAnalysis(root, spec.name, arguments, snapshot?.approval?.binding)
            }
        }
    }

    override suspend fun instructions(workspace: WorkspaceRef?): String = instructions(AgentToolScope(workspace))

    /** Guidance for the declared tools only: a disabled run_command removes its lines, not the project rules. */
    override suspend fun instructions(scope: AgentToolScope): String {
        val declared = tools(scope.workspace).map { it.descriptor.name }
            .filter { scope.declared?.contains(it) != false }
        if (declared.isEmpty()) return ""
        return buildList {
            add("Use the hosted project tools to read and edit the current workspace.")
            val virtual = scope.workspace?.let { workspaces.virtual(it) }
            if (virtual != null) {
                add(
                    "This is a virtual Git workspace. All reads and writes MUST use these tools; " +
                        "no direct shell or filesystem access. Builds and checks use " +
                        "configure_build/run_build. Changes are durable and private to this workspace.",
                )
            }
            add("Read AGENTS.md and CLAUDE.md when present before changes.")
            if (virtual != null) {
                for (path in listOf("AGENTS.md", "CLAUDE.md")) {
                    virtual.readForSearch(path)?.let { text ->
                        add("Project instructions from virtual $path:\n${text.take(MAX_APPROVAL_CHARS)}")
                    }
                }
            }
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

            AgentToolAction.Command -> arguments.argText("command")
        },
    )

    override suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval {
        val original = approval(spec, arguments)
        val virtual = context.workspace?.let { workspaces.virtual(it) }
        if (virtual != null && spec.name in virtualMutations) return virtualApproval(context, spec, arguments)
        return if (spec.name in MUTATIONS) {
            snapshots.snapshot(context, original, arguments)?.approval ?: original
        } else {
            original
        }
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult =
        if (name in MUTATIONS || name in virtualMutations) {
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
        val virtual = context.workspace?.let { workspaces.virtual(it) }
        return if (virtual != null) {
            executeVirtual(context, tool, arguments, virtual)
        } else {
            when {
                tool == null -> unavailable()
                name !in MUTATIONS -> tool.run(arguments)
                else -> executeCaptured(context, tool, arguments)
            }
        }
    }

    private suspend fun executeVirtual(
        context: AgentToolContext,
        tool: CodingTool?,
        arguments: JsonObject,
        virtual: io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspace,
    ): AgentToolResult {
        if (tool == null || tool.descriptor.name !in virtualMutations) return unavailable()
        val expected = virtual.revision()
        val bound = virtualApproval(context, tool.descriptor.specification(AgentToolAction.Edit), arguments)
        return if (context.authorization != bound) {
            AgentToolResult("Virtual revision or authorization changed; request the edit again", true)
        } else {
            virtualCodingTools(
                virtual,
                expected,
            ).single { it.descriptor.name == tool.descriptor.name }.run(arguments)
        }
    }

    private suspend fun executeCaptured(
        context: AgentToolContext,
        tool: CodingTool,
        arguments: JsonObject,
    ): AgentToolResult {
        val action = if (tool.descriptor.name == SHELL_TOOL) AgentToolAction.Command else AgentToolAction.Edit
        val original = approval(tool.descriptor.specification(action), arguments)
        val snapshot = snapshots.snapshot(context, original, arguments)
        return if (snapshot == null || context.authorization != snapshot.approval) {
            AgentToolResult("The project action changed after authorization; request it again", true)
        } else {
            currentCoroutineContext().ensureActive()
            val capturedTools = codingFileTools(snapshot.root, dispatchers.io) +
                CodingShellTool(snapshot.root, dispatchers.io, isControlled = context.isActionAnalysisRequired)
            capturedTools.single { it.descriptor.name == tool.descriptor.name }.run(snapshot.arguments)
        }
    }

    private suspend fun tools(workspace: WorkspaceRef?): List<CodingTool> {
        if (workspace == null || !workspaces.isAvailable) return emptyList()
        val virtual = workspaces.virtual(workspace)
        return if (virtual != null) virtualCodingTools(virtual) else physicalTools(workspace)
    }

    private suspend fun physicalTools(workspace: WorkspaceRef): List<CodingTool> {
        val directory = workspaces.resolve(workspace) ?: return emptyList()
        return withContext(dispatchers.io) {
            val root = ProjectRoot(Path.of(directory))
            codingFileTools(root, dispatchers.io) + CodingShellTool(root, dispatchers.io)
        }
    }

    private suspend fun virtualApproval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        args: JsonObject,
    ): AgentToolApproval {
        val virtual = checkNotNull(context.workspace?.let { workspaces.virtual(it) })
        val revision = virtual.revision()
        val before = if (spec.name in setOf("delete_file", "move_file")) {
            virtual.readForSearch(args.argText("path"))
        } else {
            virtual.read(args.argText("path"))
        }
        val description = "Virtual revision=$revision\nBefore:\n${before.orEmpty()}\nOperation:\n$args"
        val identity = "${virtual.ref.value}:${kotlinx.serialization.json.Json.encodeToString(context.session)}:" +
            "${context.turn.value}:${context.callId ?: "null"}:$revision:$args"
        val binding = java.security.MessageDigest.getInstance(
            "SHA-256",
        ).digest(identity.toByteArray()).joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
        return AgentToolApproval(spec.name, "Изменить виртуальный файл", description, binding)
    }
}

/** Virtual reads never touch the physical project, so the sandbox tree itself is the verified root. */
private suspend fun virtualReadAnalysis(
    virtual: io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspace,
    name: String,
    arguments: JsonObject,
): ActionAnalysis {
    val revision = virtual.revision()
    val binding = "${virtual.ref.value}:$revision:$name:$arguments".encodeUtf8().sha256().hex()
    return ActionAnalysis.Known(
        ActionEvidence(
            "Virtual project read $name: $arguments",
            listOf(ActionResource(ActionResourceKind.FileSystem, "virtual:${virtual.ref.value}", true)),
            setOf(ActionEffect.Read),
            binding,
        ),
    )
}

private val VIRTUAL_READS = setOf("read_file", "list_dir", "glob", "grep")

private fun unavailable() =AgentToolResult("Project tools are unavailable", isError = true)

private const val MAX_APPROVAL_CHARS = 4_000
private const val SHELL_TOOL = "run_command"
private val MUTATIONS = setOf("write_file", "edit_file", "delete_file", "move_file", SHELL_TOOL)
