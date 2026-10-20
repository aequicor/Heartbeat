package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Integration and conflict resolution operate on host-owned virtual revisions, never on shell arguments. */
@Inject
@ContributesIntoSet(ProfileScope::class)
internal class VirtualSandboxTools(private val workspaces: VirtualSandboxStore, private val journal: WorktreeJournal) :
    AgentToolContribution {
    override val group: String = "virtual_sandbox"
    override val title: String = "Виртуальная песочница"
    override val catalog = specs.toolCatalog()

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (workspace != null && workspaces.find(workspace) != null) specs else emptyList()

    override suspend fun instructions(workspace: WorkspaceRef?): String {
        if (workspace == null || workspaces.find(workspace) == null) return ""
        val state = workspaces.state(workspace)
        return "Integrate a finished child's virtual snapshot with sandbox_integrate using " +
            "the workspace and snapshot " +
            "from its host result note. Never use git or shell to integrate. " +
            if (state.isResolving) {
                "The user authorized conflict resolution. Read conflict files and use sandbox_resolve_conflict for " +
                    "each resolved file; unresolved paths: ${state.conflicts.joinToString(
                        ", ",
                    )}. Then continue the task."
            } else {
                ""
            }
    }

    override suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval {
        val parent = checkNotNull(context.workspace)
        val state = workspaces.state(parent)
        val child = if (spec.name == "sandbox_integrate") {
            val ref = WorkspaceRef(checkNotNull(arguments["workspace"]).jsonPrimitive.content)
            Json.encodeToString(workspaces.state(ref))
        } else {
            workspaces.find(parent)?.read(checkNotNull(arguments["path"]).jsonPrimitive.content).orEmpty()
        }
        val description = "Virtual revision=${state.revision}\nSnapshot=${state.snapshot}\n" +
            "Input=$arguments\nCurrent=$child"
        require(description.length <= MAX_VIRTUAL_ACTION_CHARS) { "SandboxActionTooLarge" }
        return AgentToolApproval(
            spec.name,
            "Изменить виртуальное состояние",
            description,
            "${state.revision}:${state.snapshot}:$arguments",
        )
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        val bound = approval(context, specs.single { it.name == name }, arguments)
        check(context.authorization == bound) { "SandboxAuthorizationChanged" }
        checkNotNull(journal.authenticated(context)) { "TurnUnavailable" }
        val parent = checkNotNull(context.workspace)
        val expectedRevision = checkNotNull(bound.binding).substringBefore(':').toLong()
        val result = when (name) {
            "sandbox_integrate" -> {
                val child = WorkspaceRef(checkNotNull(arguments["workspace"]).jsonPrimitive.content)
                val snapshot = checkNotNull(arguments["snapshot"]).jsonPrimitive.content
                val task = checkNotNull(journal.find(child))
                check(
                    snapshot in task.virtualResultSnapshots.values ||
                        (task.seal?.commit == snapshot && task.run?.outcome != null),
                ) { "SandboxResultChanged" }
                workspaces.integrate(parent, child, expectedRevision, snapshot)
            }

            "sandbox_resolve_conflict" -> workspaces.resolve(
                parent,
                checkNotNull(arguments["path"]).jsonPrimitive.content,
                arguments["content"]?.takeUnless { it.toString() == "null" }?.jsonPrimitive?.content,
                expectedRevision,
            )

            else -> error("UnknownSandboxTool")
        }
        return AgentToolResult(Json.encodeToString(result))
    }

    private companion object {
        val specs = listOf(
            AgentToolSpec(
                "sandbox_integrate",
                "Integrate a completed child's immutable virtual result",
                Json.parseToJsonElement(
                    """
                    {"type":"object","properties":{
                      "workspace":{"type":"string"},"snapshot":{"type":"string"}},
                      "required":["workspace","snapshot"],"additionalProperties":false}
                    """.trimIndent(),
                ).jsonObject,
                AgentToolAction.Edit,
            ),
            AgentToolSpec(
                "sandbox_resolve_conflict",
                "Save an explicitly authorized virtual file conflict resolution; null content deletes it",
                Json.parseToJsonElement(
                    """
                    {"type":"object","properties":{
                      "path":{"type":"string"},"content":{"type":["string","null"]}},
                      "required":["path","content"],"additionalProperties":false}
                    """.trimIndent(),
                ).jsonObject,
                AgentToolAction.Edit,
            ),
        )
    }
}

private const val MAX_VIRTUAL_ACTION_CHARS = 128 * 1024
