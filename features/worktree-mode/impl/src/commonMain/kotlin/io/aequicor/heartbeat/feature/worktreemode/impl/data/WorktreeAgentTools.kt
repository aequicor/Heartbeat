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
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCatalogEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
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
    override val group: String = "worktree"
    override val title: String = "Рабочие копии и сборки"
    override val catalog: List<ToolCatalogEntry> get() = toolSpecs.toolCatalog()

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
            "inside a managed project workspace; cache paths are relative to the original " +
            "project or absolute, and {cache:id} " +
            "expands in argv/environment. Use run_build for builds, tests and related exclusive operations, and " +
            "run_build waits for the build and returns its terminal result; only when it is still pending after ten " +
            "minutes, call build_status with the returned operation id (it waits again; do not poll in a loop " +
            "with short waits). Queued, WaitingForResource and Running are pending; " +
            "Completed, Failed, Cancelled and Unknown are terminal. Once terminal, handle the result and continue " +
            "the task or give the final report. These operations do not publish scheduler events: use build_status, " +
            "not scheduler_sleep, to wait for them. Keep outputs and project state checkout-local; share only " +
            "genuine caches. " +
            "Configure a bounded timeoutMillis for every command. Do not detach build processes or start " +
            "background daemons. Use only the build and validation rules of this project; caches are optional. " +
            "Declare each command's kind (Check, Compile, Test, Assemble, Native): the queue shares CPU and memory " +
            "within this project by it. Mark a cache concurrent only when its tool coordinates concurrent " +
            "processes itself, such as GRADLE_USER_HOME; other caches serialize builds. " +
            "Native commands bypass coordination, so obey this workflow."
        val task = journal.find(workspace)
        if (task?.virtualSandbox !=
            null
        ) {
            return "This session owns a virtual Git workspace. Use hosted file tools for all " +
                "project reads and edits. " +
                "No source checkout exists for this session. $workflow " +
                "When finished call mark_task_complete; the host saves a local result branch " +
                "and hands it to the user. " +
                "Do not create a PR, merge, push or switch the user's checkout."
        }
        val sandbox = if (task?.isSandbox == true) {
            " This checkout is a sandbox that keeps only sources: never build, test or run Gradle natively here. " +
                "run_build snapshots the current files (uncommitted changes included, ignored files excluded) and " +
                "builds them in a shared warm build slot; outputs stay in that slot, so rely on the build output."
        } else {
            ""
        }
        return if (isolated) {
            "This session owns an isolated Git worktree.$sandbox $workflow Only after the user task is done call " +
                "mark_task_complete with a concise summary; a normal reply does not mark it done. " +
                "A requested pull request must include pullRequestUrl in mark_task_complete; a " +
                "local merge must not push."
        } else {
            workflow
        }
    }

    override suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval {
        val approval = when (spec.name) {
            "configure_build" -> {
                val plan = Json.decodeFromJsonElement<WorktreeBuildPlan>(arguments)
                reviewableApproval(
                    spec,
                    "Configure build",
                    "Save this build plan:\n${approvalJson.encodeToString(plan)}",
                )
            }

            "cancel_build" -> cancelApproval(context, spec, arguments)

            "run_build" -> buildApproval(context, spec, arguments)

            else -> approval(spec, arguments)
        }
        return approval
    }

    private suspend fun buildApproval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval {
        val command = arguments["command"]?.jsonPrimitive?.content ?: return approval(spec, arguments)
        val execution = journal.buildPreview(context, command) ?: return approval(spec, arguments)
        // A sandbox command runs from a snapshot in a shared slot that keeps outputs of other tasks' builds.
        val location = execution.sandbox?.let { sandbox ->
            "Runs from a snapshot of the sandbox's current files in a shared warm build slot " +
                "(created with git worktree add on first use).\n" +
                "Slot pool: ${approvalJson.encodeToString(sandbox.pool)}\n" +
                "Directory inside the slot: ${approvalJson.encodeToString(sandbox.relativeDirectory)}\n"
        } ?: "Working directory: ${approvalJson.encodeToString(execution.directory)}\n"
        return reviewableApproval(
            spec,
            "Run build",
            "Configuration revision=${execution.configurationRevision}\n" +
                location +
                "Command: ${approvalJson.encodeToString(execution.command)}\n" +
                "Exclusive resources: ${approvalJson.encodeToString(execution.resources)}",
            binding = if (execution.sandbox?.baseCommit != null) {
                "${execution.configurationRevision}@${execution.projectConfigurationRevision}@" +
                    execution.sandbox.snapshot
            } else {
                execution.configurationRevision.toString()
            },
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
        // Cancelling only stops the task's own build, so it is never refused because the shared project
        // configuration moved on since the build started: the build's own record then describes it.
        val execution = try {
            journal.buildPreview(context, known.command)
                ?.takeIf { it.configurationRevision == known.configurationRevision }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalStateException) {
            log.w(error) { "Build preview unavailable for a cancellation; describing the build record" }
            null
        }
        val details = if (execution == null) {
            "Command: ${approvalJson.encodeToString(known.command)}\n" +
                "Configuration revision=${known.configurationRevision}"
        } else {
            "Configuration revision=${execution.configurationRevision}\n" +
                "Working directory: ${approvalJson.encodeToString(execution.directory)}\n" +
                "Command: ${approvalJson.encodeToString(execution.command)}\n" +
                "Exclusive resources: ${approvalJson.encodeToString(execution.resources)}"
        }
        return reviewableApproval(
            spec,
            "Cancel build",
            "Stop this build operation and its process tree:\n" +
                "Operation: ${approvalJson.encodeToString(operation)}\n" + details,
            binding = "$operation:${known.configurationRevision}",
        )
    }

    /** Mutating previews are complete; invisible text is escaped without truncating the authorized operation. */
    private fun reviewableApproval(
        spec: AgentToolSpec,
        title: String,
        description: String,
        binding: String? = null,
    ): AgentToolApproval {
        val safeTitle = title.visibleApprovalText()
        val safeDescription = description.visibleApprovalText()
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
            // Revalidate the exact operation before tracking or mutating its task.
            val isAuthorized = name !in mutatingTools ||
                context.authorization == approval(context, toolSpecs.single { it.name == name }, arguments)
            if (!isAuthorized) return failure("AuthorizationChanged")
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
        // The machine would reject it too, but an identical repeated failure changes no state and only times out.
        val rejection = journal.planRejection(task.chatId, plan)
        return if (rejection != null) failure(planRejectionText(rejection)) else propose(task, context, plan)
    }

    private suspend fun propose(
        task: WorktreeTask,
        context: AgentToolContext,
        plan: WorktreeBuildPlan,
    ): AgentToolResult {
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
        val binding = context.authorization?.binding?.split('@').orEmpty()
        val expected = binding.firstOrNull()?.toLongOrNull()
            ?: return failure("BuildAuthorizationUnavailable")
        if (expected != task.buildConfigurationRevision) return failure("BuildConfigurationChanged")
        val command = arguments["command"]?.jsonPrimitive?.content ?: return failure("MissingCommand")
        val operation = Uuid.random().toString()
        val sent = machines.send(
            WorktreeMachineKey,
            WorktreeIntent.Public.RunBuild(
                task.chatId,
                operation,
                command,
                expected,
                task.identity(context),
                expectedSnapshot = binding.getOrNull(2),
                expectedProjectRevision = binding.getOrNull(1)?.toLongOrNull(),
            ),
        )
        if (sent != SendResult.Accepted) return failure("WorktreeUnavailable")
        // The enqueue acknowledgement is durable: the build belongs to the queue, not to this call.
        val queued = withTimeoutOrNull(COMMAND_TIMEOUT) {
            awaitTask(task.chatId) { operation in it.builds }
        } ?: return failure("BuildEnqueueTimedOut")
        // Waiting here replaces a polling loop; cancelling the call stops only the wait, never the build.
        return buildResult(awaitBuild(task.chatId, operation, BUILD_WAIT) ?: queued, operation)
    }

    private suspend fun awaitBuild(chatId: String, operation: String, wait: Long): WorktreeTask? =
        withTimeoutOrNull(wait) { awaitTask(chatId) { it.builds[operation]?.phase in terminal } }
            ?: journal.taskProjection(chatId)

    private fun buildResult(task: WorktreeTask, operation: String): AgentToolResult {
        val build = checkNotNull(task.builds[operation])
        val text = Json.encodeToString(build)
        return if (build.phase in terminal) {
            AgentToolResult(text, isError = build.phase in setOf(WorktreeBuildPhase.Failed, WorktreeBuildPhase.Unknown))
        } else {
            AgentToolResult(
                "$text\nStill ${build.phase} after the wait; call build_status with this operation to keep waiting.",
            )
        }
    }

    private fun WorktreeTask.identity(context: AgentToolContext): WorktreeRunIdentity =
        WorktreeRunIdentity(checkNotNull(run).request, context.session, context.turn)

    private suspend fun status(task: WorktreeTask, arguments: JsonObject): AgentToolResult {
        val operation = arguments["operation"]?.jsonPrimitive?.content ?: return failure("MissingOperation")
        val known = task.builds[operation] ?: return failure("UnknownBuildOperation")
        val wait = (arguments["waitMs"]?.jsonPrimitive?.longOrNull ?: BUILD_WAIT).coerceIn(0L, BUILD_WAIT)
        val current = if (wait > 0 && known.phase !in terminal) {
            awaitBuild(task.chatId, operation, wait) ?: task
        } else {
            task
        }
        return buildResult(current, operation)
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

    /** A rejected plan names what to change: the bare code does not tell the agent what is missing. */
    private fun planRejectionText(code: String): String = when (code) {
        "NeedsConfiguration" ->
            "NeedsConfiguration: configure at least one project command with executable and arguments; " +
                "caches are optional"

        else -> code
    }

    private companion object {
        const val COMMAND_TIMEOUT = 30_000L
        const val HEX_RADIX = 16
        const val UNICODE_ESCAPE_WIDTH = 4
        const val MAX_SUMMARY = 4_096

        /** Longest wait of one run_build/build_status call: as long as a synchronous run_command. */
        const val BUILD_WAIT = 600_000L
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
                "Declare the actual foreground build commands, shared caches and exclusive resources. " +
                    "Every declared cache must be referenced as {cache:<id>} in the arguments or environment " +
                    "of at least one command (e.g. GRADLE_USER_HOME={cache:gradle}), otherwise the call fails " +
                    "with UnusedBuildCache; omit caches you do not use.",
                Json.parseToJsonElement(
                    """{
                        "type":"object","properties":{
                            "system":{"type":"string"},
                            "commands":{"type":"array","items":{"type":"object","properties":{
                                "id":{"type":"string"},"executable":{"type":"string"},
                                "arguments":{"type":"array","items":{"type":"string"}},
                                "directory":{"type":"string"},
                                "environment":{"type":"object","additionalProperties":{"type":"string"}},
                                "timeoutMillis":{"type":"integer","minimum":1000,"maximum":86400000},
                                "kind":{"type":"string",
                                    "enum":["Check","Compile","Test","Assemble","Native"]}
                            },"required":["id","executable"],"additionalProperties":false}},
                            "caches":{"type":"array","items":{"type":"object","properties":{
                                "id":{"type":"string"},"directory":{"type":"string"},
                                "concurrent":{"type":"boolean"}
                            },"required":["id","directory"],"additionalProperties":false}},
                            "exclusiveResources":{"type":"array","items":{"type":"string"}}
                        },"required":["system","commands"],"additionalProperties":false
                    }
                    """.trimIndent(),
                ).jsonObject,
                AgentToolAction.Edit,
            ),
            AgentToolSpec(
                "run_build",
                "Run one configured command in this Git project's reusable build-worktree pool and wait for its " +
                    "terminal result (up to ten minutes). A pending result names the operation for build_status.",
                schema("command"),
                AgentToolAction.Command,
            ),
            AgentToolSpec(
                "build_status",
                "Wait for a build operation that run_build left pending: by default up to ten minutes for a " +
                    "terminal result; waitMs=0 reads it at once. Completed, Failed, Cancelled and Unknown are " +
                    "terminal: process the result without sleeping.",
                Json.parseToJsonElement(
                    """{"type":"object","properties":{
                        "operation":{"type":"string"},"waitMs":{"type":"integer","minimum":0,"maximum":600000}
                    },"required":["operation"],"additionalProperties":false}""",
                ).jsonObject,
            ),
            AgentToolSpec(
                "cancel_build",
                "Request cancellation of a build operation owned by this task; build_status waits for termination.",
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

        /** Tools that change the saved plan or the machine's processes: reviewed in automatic mode. */
        val mutatingTools = setOf("configure_build", "run_build", "cancel_build")

        fun schema(field: String): JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{"$field":{"type":"string"}},
                "required":["$field"],"additionalProperties":false}""",
        ).jsonObject
    }
}
