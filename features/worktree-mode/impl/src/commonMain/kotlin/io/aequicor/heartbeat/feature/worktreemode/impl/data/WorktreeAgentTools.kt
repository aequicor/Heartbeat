package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPlan
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeModeEnabled
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunIdentity
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import io.aequicor.heartbeat.feature.worktreemode.api.hasActiveBuilds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.uuid.Uuid

/** Hosted protocol; native context selects the task, never user-controlled JSON identifiers or filesystem paths. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class WorktreeAgentTools(
    private val journal: WorktreeJournal,
    private val machines: MachineRegistry,
    private val toggles: FeatureToggles,
    private val workspaces: LocalWorkspaces,
) : AgentToolContribution {
    private val log = Log.tag("WorktreeAgentTools")

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> {
        val task = journal.find(workspace)
        return when {
            task != null -> if (task.isIsolated) toolSpecs else buildToolSpecs
            availableProject(workspace) -> buildToolSpecs
            else -> emptyList()
        }
    }

    private suspend fun availableProject(workspace: WorkspaceRef?): Boolean =
        workspace != null && newMainTrackingEnabled() && workspaces.resolve(workspace) != null

    private suspend fun newMainTrackingEnabled(): Boolean = toggles.get(WorktreeModeEnabled) && workspaces.isAvailable

    override suspend fun instructions(workspace: WorkspaceRef?): String {
        if (specifications(workspace).isEmpty()) return ""
        val isolated = journal.find(workspace)?.isIsolated == true
        val workflow = "First inspect the project and configure_build with the actual " +
            "build system, foreground argv commands, shared cache locations and exclusive resources. Commands run " +
            "inside this checkout; cache paths are relative to the original project or absolute, and {cache:id} " +
            "expands in argv/environment. Use run_build for builds, tests and related exclusive operations, and " +
            "build_status with the returned operation id to poll completion. run_build acknowledges the durable " +
            "queue immediately. Keep outputs and project state checkout-local; share only genuine caches. " +
            "Configure a bounded timeoutMillis for every command. Do not detach build processes or start " +
            "background daemons. Gradle uses GRADLE_USER_HOME={cache:id}, --no-daemon and --build-cache. " +
            "Native commands bypass coordination, so obey this workflow."
        return if (isolated) {
            "This session owns an isolated Git worktree. $workflow Only after the user task is done call " +
                "mark_task_complete with a concise summary; a normal reply does not mark it done. " +
                "A requested pull request must include pullRequestUrl in mark_task_complete; a local merge must not push."
        } else {
            workflow
        }
    }

    override suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval = when (spec.name) {
        "configure_build" -> {
            val plan = Json.decodeFromJsonElement<WorktreeBuildPlan>(arguments)
            reviewableApproval(spec, "Configure build", "Save this build plan:\n${approvalJson.encodeToString(plan)}")
        }

        "cancel_build" -> cancelApproval(context, spec, arguments)

        "run_build" -> buildApproval(context, spec, arguments)

        else -> approval(spec, arguments)
    }

    private suspend fun buildApproval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval {
        val command = arguments["command"]?.jsonPrimitive?.content ?: return approval(spec, arguments)
        val execution = journal.buildPreview(context, command) ?: return approval(spec, arguments)
        return reviewableApproval(
            spec,
            "Run build",
            "Configuration revision=${execution.configurationRevision}\n" +
                "Working directory: ${approvalJson.encodeToString(execution.directory)}\n" +
                "Command: ${approvalJson.encodeToString(execution.command)}\n" +
                "Exclusive resources: ${approvalJson.encodeToString(execution.resources)}",
            binding = execution.configurationRevision.toString(),
        )
    }

    private suspend fun cancelApproval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval {
        val operation = arguments["operation"]?.jsonPrimitive?.content ?: error("MissingOperation")
        val task = journal.authenticated(context) ?: error("TurnUnavailable")
        val known = task.builds[operation] ?: error("UnknownBuildOperation")
        val execution = journal.buildPreview(context, known.command) ?: error("BuildConfigurationUnavailable")
        check(execution.configurationRevision == known.configurationRevision) { "BuildConfigurationChanged" }
        return reviewableApproval(
            spec,
            "Cancel build",
            "Stop this build operation and its process tree:\n" +
                "Operation: ${approvalJson.encodeToString(operation)}\n" +
                "Configuration revision=${execution.configurationRevision}\n" +
                "Working directory: ${approvalJson.encodeToString(execution.directory)}\n" +
                "Command: ${approvalJson.encodeToString(execution.command)}\n" +
                "Exclusive resources: ${approvalJson.encodeToString(execution.resources)}",
            binding = "$operation:${known.configurationRevision}",
        )
    }

    /** Mutating previews are complete; invisible text is escaped and oversized actions fail before the gate. */
    private fun reviewableApproval(
        spec: AgentToolSpec,
        title: String,
        description: String,
        binding: String? = null,
    ): AgentToolApproval {
        val safeTitle = title.visibleApprovalText()
        val safeDescription = description.visibleApprovalText()
        check(safeTitle.length + safeDescription.length <= MAX_APPROVAL_CHARS) { "BuildApprovalTooLarge" }
        return AgentToolApproval(spec.name, safeTitle, safeDescription, binding)
    }

    private fun String.visibleApprovalText(): String = buildString {
        for (character in this@visibleApprovalText) {
            val code = character.code
            if (
                character != '\n' && (invisibleApprovalRanges.any { code in it } || code in invisibleApprovalCodes)
            ) {
                append("\\u").append(code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_WIDTH, '0'))
            } else {
                append(character)
            }
        }
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult =
        try {
            val task = authenticateOrTrack(context)
            if (task == null) failure("TurnUnavailable") else dispatch(task, context, name, arguments)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(IllegalStateException("WorktreeToolFailed (${error::class.simpleName.orEmpty()})")) {
                "Hosted worktree tool failed name=$name"
            }
            failure("WorktreeToolFailed")
        }

    private suspend fun authenticateOrTrack(context: AgentToolContext): WorktreeTask? {
        val authenticated = journal.authenticated(context)
        if (authenticated != null) return authenticated
        val workspace = context.workspace
        val known = journal.find(workspace)
        val isTrackable = workspace != null && known?.isIsolated != true
        val isEnabled = known != null || toggles.get(WorktreeModeEnabled)
        if (isTrackable && isEnabled) {
            val request = context.request ?: RequestId("hosted-${context.turn.value}")
            val sent = machines.send(
                WorktreeMachineKey,
                WorktreeIntent.Public.TrackMainSession(workspace, context.session, request, context.turn),
            )
            if (sent == SendResult.Accepted) {
                withTimeoutOrNull(COMMAND_TIMEOUT) {
                    awaitMatching { it.matchesContext(context) }
                }
            }
        }
        return journal.authenticated(context)
    }

    private fun WorktreeTask.matchesContext(context: AgentToolContext): Boolean =
        executionWorkspace == context.workspace && run?.session == context.session && run?.turn == context.turn

    private suspend fun dispatch(
        task: WorktreeTask,
        context: AgentToolContext,
        name: String,
        arguments: JsonObject,
    ): AgentToolResult = when (name) {
        "configure_build" -> configure(task, context, arguments)
        "run_build" -> build(task, context, arguments)
        "build_status" -> status(task, arguments)
        "cancel_build" -> cancel(task, arguments)
        "worktree_status" -> AgentToolResult(Json.encodeToString(task))
        "mark_task_complete" -> if (task.isIsolated) complete(task, context, arguments) else failure("NotIsolatedTask")
        else -> failure("UnknownTool")
    }
    private suspend fun configure(
        task: WorktreeTask,
        context: AgentToolContext,
        arguments: JsonObject,
    ): AgentToolResult {
        if (task.hasActiveBuilds()) return failure("BuildConfigurationBusy")
        val plan = Json.decodeFromJsonElement<WorktreeBuildPlan>(arguments)
        val sent = machines.send(
            WorktreeMachineKey,
            WorktreeIntent.Public.ProposeBuildPlan(task.chatId, plan, task.identity(context)),
        )
        if (sent != SendResult.Accepted) return failure("WorktreeUnavailable")
        val configured = withTimeoutOrNull(COMMAND_TIMEOUT) {
            awaitTask(
                task.chatId,
            ) { it.configurationMatches(task, plan) || (it.revision > task.revision && it.failure != null) }
        } ?: return failure("ConfigurationTimedOut")
        return if (configured.configurationMatches(task, plan)) {
            AgentToolResult(
                "Build configuration saved and validated; revision=${configured.buildConfigurationRevision}",
            )
        } else {
            failure(configured.failure ?: "ConfigurationRejected")
        }
    }

    private fun WorktreeTask.configurationMatches(previous: WorktreeTask, plan: WorktreeBuildPlan): Boolean =
        buildConfigurationRevision > previous.buildConfigurationRevision && buildPlan == plan && isBuildApproved

    private suspend fun build(task: WorktreeTask, context: AgentToolContext, arguments: JsonObject): AgentToolResult {
        if (task.run?.kind == WorktreeRunKind.Merge) return failure("MergeInProgress")
        if (!task.isBuildApproved || task.buildPlan == null) return failure("NeedsConfiguration")
        val expected = context.authorization?.binding?.toLongOrNull() ?: return failure("BuildAuthorizationUnavailable")
        if (expected != task.buildConfigurationRevision) return failure("BuildConfigurationChanged")
        val command = arguments["command"]?.jsonPrimitive?.content ?: return failure("MissingCommand")
        val operation = Uuid.random().toString()
        val sent = machines.send(
            WorktreeMachineKey,
            WorktreeIntent.Public.RunBuild(task.chatId, operation, command, expected, task.identity(context)),
        )
        if (sent != SendResult.Accepted) return failure("WorktreeUnavailable")
        // The enqueue acknowledgement is durable. Long work continues independently of this HTTP/MCP call.
        val completed = withTimeoutOrNull(COMMAND_TIMEOUT) {
            awaitTask(task.chatId) { operation in it.builds }
        } ?: return failure("BuildEnqueueTimedOut")
        val result = checkNotNull(completed.builds[operation])
        return AgentToolResult(
            Json.encodeToString(result),
            isError = result.phase in setOf(WorktreeBuildPhase.Failed, WorktreeBuildPhase.Unknown),
        )
    }

    private fun WorktreeTask.identity(context: AgentToolContext): WorktreeRunIdentity =
        WorktreeRunIdentity(checkNotNull(run).request, context.session, context.turn)

    private suspend fun status(task: WorktreeTask, arguments: JsonObject): AgentToolResult {
        val operation = arguments["operation"]?.jsonPrimitive?.content ?: return failure("MissingOperation")
        val known = task.builds[operation] ?: return failure("UnknownBuildOperation")
        val wait = (arguments["waitMs"]?.jsonPrimitive?.longOrNull ?: 0L).coerceIn(0L, STATUS_TIMEOUT)
        val current = if (wait > 0 && known.phase !in terminal) {
            withTimeoutOrNull(
                wait,
            ) {
                awaitTask(
                    task.chatId,
                ) { it.builds[operation]?.phase in terminal }
            } ?: journal.taskProjection(task.chatId) ?: task
        } else {
            task
        }
        return AgentToolResult(Json.encodeToString(checkNotNull(current.builds[operation])))
    }

    private suspend fun cancel(task: WorktreeTask, arguments: JsonObject): AgentToolResult {
        val operation = arguments["operation"]?.jsonPrimitive?.content ?: return failure("MissingOperation")
        if (operation !in task.builds) return failure("UnknownBuildOperation")
        val sent = machines.send(WorktreeMachineKey, WorktreeIntent.Public.CancelBuild(task.chatId, operation))
        return if (sent == SendResult.Accepted) {
            AgentToolResult(
                "Cancellation requested; build_status shows the confirmed outcome",
            )
        } else {
            failure("WorktreeUnavailable")
        }
    }

    private suspend fun complete(
        task: WorktreeTask,
        context: AgentToolContext,
        arguments: JsonObject,
    ): AgentToolResult {
        val summary =
            arguments["summary"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it.length <= MAX_SUMMARY }
                ?: return failure("InvalidSummary")
        val url = arguments["pullRequestUrl"]?.jsonPrimitive?.content
        val sent = machines.send(
            WorktreeMachineKey,
            WorktreeIntent.Public.TaskCompleteSignaled(task.chatId, context.session, context.turn, summary, url),
        )
        if (sent != SendResult.Accepted) return failure("WorktreeUnavailable")
        val result = withTimeoutOrNull(COMMAND_TIMEOUT) {
            awaitTask(task.chatId) { it.run?.turn == context.turn && it.run?.isCompletionSignaled == true }
        } ?: return failure("CompletionSignalTimedOut")
        return AgentToolResult(
            "Task completion recorded; native completion and build termination settle the task. Phase=${result.phase}",
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitTask(chatId: String, predicate: (WorktreeTask) -> Boolean): WorktreeTask =
        machines.observe(WorktreeMachineKey).flatMapLatest { ref -> ref?.state ?: flowOf(WorktreeState.Idle) }
            .map { state -> (state as? WorktreeState.Ready)?.tasks?.get(chatId) }.filterNotNull().first(predicate)

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitMatching(predicate: (WorktreeTask) -> Boolean): WorktreeTask =
        machines.observe(WorktreeMachineKey).flatMapLatest { ref -> ref?.state ?: flowOf(WorktreeState.Idle) }
            .map { state ->
                (state as? WorktreeState.Ready)?.tasks?.values?.firstOrNull(
                    predicate,
                )
            }.filterNotNull().first()

    private fun failure(code: String): AgentToolResult = AgentToolResult(code, isError = true)

    private companion object {
        const val COMMAND_TIMEOUT = 30_000L
        const val MAX_APPROVAL_CHARS = 16_384
        const val HEX_RADIX = 16
        const val UNICODE_ESCAPE_WIDTH = 4
        const val MAX_SUMMARY = 4_096
        const val STATUS_TIMEOUT = 10_000L
        val approvalJson = Json { encodeDefaults = true }
        val invisibleApprovalRanges = listOf(0x00..0x1F, 0x7F..0x9F, 0x200B..0x200F, 0x2028..0x202E, 0x2060..0x206F)
        val invisibleApprovalCodes = setOf(0x061C, 0xFEFF)
        val terminal = setOf(
            WorktreeBuildPhase.Completed,
            WorktreeBuildPhase.Cancelled,
            WorktreeBuildPhase.Failed,
            WorktreeBuildPhase.Unknown,
        )
        val toolSpecs = listOf(
            AgentToolSpec(
                "configure_build",
                "Declare the actual foreground build commands, shared caches and exclusive resources.",
                Json.parseToJsonElement(
                    """{
                        "type":"object","properties":{
                            "system":{"type":"string"},
                            "commands":{"type":"array","items":{"type":"object","properties":{
                                "id":{"type":"string"},"executable":{"type":"string"},
                                "arguments":{"type":"array","items":{"type":"string"}},
                                "directory":{"type":"string"},
                                "environment":{"type":"object","additionalProperties":{"type":"string"}},
                                "timeoutMillis":{"type":"integer","minimum":1000,"maximum":86400000}
                            },"required":["id","executable"],"additionalProperties":false}},
                            "caches":{"type":"array","items":{"type":"object","properties":{
                                "id":{"type":"string"},"directory":{"type":"string"}
                            },"required":["id","directory"],"additionalProperties":false}},
                            "exclusiveResources":{"type":"array","items":{"type":"string"}}
                        },"required":["system","commands","caches"],"additionalProperties":false
                    }
                    """.trimIndent(),
                ).jsonObject,
                AgentToolAction.Edit,
            ),
            AgentToolSpec(
                "run_build",
                "Run one configured command through the shared-resource FIFO worker.",
                schema("command"),
                AgentToolAction.Command,
            ),
            AgentToolSpec(
                "build_status",
                "Read a build operation, optionally waiting at most ten seconds for a terminal result.",
                Json.parseToJsonElement(
                    """{"type":"object","properties":{
                        "operation":{"type":"string"},"waitMs":{"type":"integer","minimum":0,"maximum":10000}
                    },"required":["operation"],"additionalProperties":false}""",
                ).jsonObject,
            ),
            AgentToolSpec(
                "cancel_build",
                "Request cancellation of a build operation owned by this task; poll build_status for termination.",
                schema("operation"),
                AgentToolAction.Command,
            ),
            AgentToolSpec(
                "worktree_status",
                "Read the isolated task and build queue status.",
                Json.parseToJsonElement(
                    """{"type":"object","properties":{},"additionalProperties":false}""",
                ).jsonObject,
            ),
            AgentToolSpec(
                "mark_task_complete",
                "Explicitly signal that this task or requested completion action is finished.",
                Json.parseToJsonElement(
                    """{"type":"object","properties":{
                        "summary":{"type":"string"},"pullRequestUrl":{"type":"string"}
                    },"required":["summary"],"additionalProperties":false}""",
                ).jsonObject,
            ),
        )
        val buildToolSpecs = toolSpecs.filterNot { it.name == "mark_task_complete" }

        fun schema(field: String): JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{"$field":{"type":"string"}},
                "required":["$field"],"additionalProperties":false}""",
        ).jsonObject
    }
}
