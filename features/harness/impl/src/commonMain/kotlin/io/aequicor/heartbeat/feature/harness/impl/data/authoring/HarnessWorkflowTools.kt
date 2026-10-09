package io.aequicor.heartbeat.feature.harness.impl.data.authoring

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsOutput
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.StepPhase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrigin
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRejection
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowViewer
import io.aequicor.heartbeat.feature.harness.api.workflow.isVisibleTo
import io.aequicor.heartbeat.feature.harness.impl.data.run.HarnessWorkflowLaunches
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessRunsMachine
import io.aequicor.heartbeat.feature.scheduler.api.HelperAgents
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Agent-started workflow runs. Starting is a Command in the trust table and binds the approval to the harness
 * revision and workflow item; status and cancellation are limited to runs this exact session started.
 * Inputs and results are never logged.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class HarnessWorkflowTools(
    private val toggles: FeatureToggles,
    private val library: Lazy<HarnessLibraryClient>,
    private val launches: Lazy<HarnessWorkflowLaunches>,
    private val runs: Lazy<HarnessRunsMachine>,
    private val helpers: Lazy<HelperAgents>,
    private val origins: Lazy<HarnessRequestOrigins>,
) : AgentToolContribution {
    private val log = Log.tag("HarnessTools")
    override val group: String = "harness"
    override val title: String = "Харнессы"
    override val isDetachedSupported: Boolean = true
    override val catalog get() = HARNESS_WORKFLOW_SPECS.toolCatalog()

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (toggles.get(HarnessEnabled)) HARNESS_WORKFLOW_SPECS else emptyList()

    override suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval {
        if (spec.name != HarnessTools.WORKFLOW_START) return AgentToolApproval(spec.name, spec.description)
        val target = target(arguments) ?: return AgentToolApproval(spec.name, spec.description, binding = "invalid")
        val (harness, workflow) = target
        val canHost = helpers.value.canHost(context.session)
        val text = buildString {
            if (!canHost) append("Helper chats cannot be hosted for this chat; the start will fail.\n\n")
            append("Workflow ").append(harness.name.value).append('/').append(workflow.name.value)
            if (workflow.description.isNotEmpty()) append(" — ").append(workflow.description)
            append("\nIt starts helper chats with Ask trust and wakes this chat with the result.")
            append("\n\nInput: ").append(input(arguments).toString())
        }
        return AgentToolApproval(
            spec.name,
            "Start workflow ${harness.name.value}/${workflow.name.value}",
            text,
            "harness=${harness.id.value}@${harness.revision};workflow=${workflow.id.value}",
        )
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        if (!toggles.get(HarnessEnabled)) return failure("Harnesses are turned off")
        return when (name) {
            HarnessTools.WORKFLOW_START -> start(context, arguments)
            HarnessTools.WORKFLOW_STATUS -> status(context, arguments)
            HarnessTools.WORKFLOW_CANCEL -> cancel(context, arguments)
            else -> failure("Unknown tool")
        }
    }

    private suspend fun start(context: AgentToolContext, arguments: JsonObject): AgentToolResult {
        val (harness, workflow) = target(arguments) ?: return failure("No such enabled workflow; see harness_get")
        val expected = "harness=${harness.id.value}@${harness.revision};workflow=${workflow.id.value}"
        if (context.authorization?.binding != expected) {
            return failure("The workflow changed while awaiting approval; request it again")
        }
        val request = context.request ?: return failure("Workflows can only be started from an accepted turn")
        if (!helpers.value.canHost(context.session)) return failure("Helper chats cannot be hosted for this chat")
        return try {
            val launch = launches.value.prepare(
                harness.id,
                workflow.name,
                input(arguments),
                context.session,
                context.workspace,
                RequestInitiator(context.session, request),
                origins.value.origin(context.session, request),
                WorkflowOrigin.Agent,
            )
            val result = launches.value.start(launch)
            log.i { "Agent workflow start submitted confirmed=${result.isConfirmed}" }
            val wake = if (result.hasWake) {
                "This chat is woken when it finishes."
            } else {
                "Poll harness_workflow_status for the result."
            }
            AgentToolResult("Started run ${result.run.value}. $wake")
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            log.w(IllegalArgumentException(error::class.simpleName)) { "Workflow input rejected" }
            failure("The input does not match the workflow schema")
        } catch (error: IllegalStateException) {
            log.w(IllegalStateException(error::class.simpleName)) { "Workflow start refused" }
            failure("The workflow cannot start now: it is disabled, at capacity or no longer active here")
        }
    }

    private suspend fun status(context: AgentToolContext, arguments: JsonObject): AgentToolResult {
        val run = visible(context, arguments) ?: return failure("No run with this id was started by this chat")
        val text = buildString {
            append("Run ").append(run.id.value).append(": ")
            append(
                when (val status = run.status) {
                    WorkflowStatus.Running -> if (run.cancellation != null) "cancelling" else "running"
                    is WorkflowStatus.Completed -> "completed"
                    is WorkflowStatus.Failed -> "failed (${status.reason.name.lowercase()})"
                },
            )
            val done = run.steps.count { it.phase == StepPhase.Completed }
            val failed = run.steps.count { it.phase == StepPhase.Failed }
            append("\nSteps: ").append(done).append(" done, ").append(failed).append(" failed, ")
            append(run.steps.size - done - failed).append(" in progress")
            val waiting = run.awaiting.values.sumOf { it.size }
            if (waiting > 0) append("\nHelpers await ").append(waiting).append(" permission decisions of the user")
            (run.status as? WorkflowStatus.Completed)?.let { append("\nResult: ").append(it.result.toString()) }
        }
        return AgentToolResult(text)
    }

    private suspend fun cancel(context: AgentToolContext, arguments: JsonObject): AgentToolResult {
        val run = visible(context, arguments) ?: return failure("No run with this id was started by this chat")
        if (run.status != WorkflowStatus.Running) return failure("The run has already finished")
        val request = RequestId(Uuid.random().toHexString())
        val machine = runs.value
        val outcome = coroutineScope {
            val receipt = async(start = CoroutineStart.UNDISPATCHED) {
                machine.outputs.first {
                    (it is HarnessRunsOutput.CancellationRequested && it.requestId == request) ||
                        (it is HarnessRunsOutput.Rejected && it.requestId == request)
                }
            }
            try {
                val sent = machine.send(
                    HarnessRunsIntent.Public.Cancel(request, run.id, WorkflowViewer.Agent(context.session)),
                )
                if (sent == SendResult.Accepted) withTimeoutOrNull(OUTCOME_TIMEOUT) { receipt.await() } else null
            } finally {
                receipt.cancel()
            }
        }
        log.i { "Agent workflow cancel requested accepted=${outcome is HarnessRunsOutput.CancellationRequested}" }
        return when (outcome) {
            is HarnessRunsOutput.CancellationRequested ->
                AgentToolResult("Cancelling run ${run.id.value}; helpers are being stopped.")

            is HarnessRunsOutput.Rejected -> failure(
                if (outcome.reason == WorkflowRejection.AlreadyFinished) {
                    "The run has already finished"
                } else {
                    "The run was not cancelled"
                },
            )

            else -> failure("Cancellation is not confirmed yet; check harness_workflow_status")
        }
    }

    private suspend fun visible(context: AgentToolContext, arguments: JsonObject): WorkflowRun? {
        val id = arguments.text("run")?.let { parseRunId(it) } ?: return null
        val state = withTimeoutOrNull(OUTCOME_TIMEOUT) {
            runs.value.state.first { it is HarnessRunsState.Ready || it is HarnessRunsState.Failed }
        } as? HarnessRunsState.Ready ?: return null
        val viewer = WorkflowViewer.Agent(context.session)
        return state.runs.singleOrNull { it.id == id }?.takeIf { it.isVisibleTo(viewer) }
    }

    private fun target(arguments: JsonObject): Pair<Harness, HarnessItem.Workflow>? {
        val state = library.value.current ?: return null
        val harness = state.harnesses.map { it.harness }
            .singleOrNull { it.name.value == arguments.text("harness") && it.isEnabled } ?: return null
        val name = arguments.text("workflow")?.takeIf { it.matches(ITEM_NAME) }?.let(::ItemName) ?: return null
        val workflow = harness.items.singleOrNull { it.name == name && it.isEnabled } as? HarnessItem.Workflow
            ?: return null
        return harness to workflow
    }

    private fun input(arguments: JsonObject): JsonObject = arguments["input"] as? JsonObject ?: JsonObject(emptyMap())

    private fun failure(message: String) = AgentToolResult(message, isError = true)
}

/** Model text is never trusted as a run identity beyond its syntax; visibility is checked separately. */
private fun parseRunId(value: String): RunId? = if (value.matches(RUN_ID)) RunId(value) else null

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

private val OUTCOME_TIMEOUT = 5.seconds
private val ITEM_NAME = Regex("[a-z][a-z0-9_]{0,31}")
private val RUN_ID = Regex("wf_[a-zA-Z0-9_]{1,64}")
