package io.aequicor.heartbeat.feature.harness.impl.data.authoring

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAutomaticAuthorization
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
import io.aequicor.heartbeat.feature.harness.impl.data.run.ApprovedWorkflow
import io.aequicor.heartbeat.feature.harness.impl.data.run.HarnessWorkflowLaunches
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCodeReviews
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessLibraryClient
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessRunsMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.isWorkflowInput
import io.aequicor.heartbeat.feature.scheduler.api.HelperAgents
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Agent-started workflow mutations require an automatic safety verdict for the exact source, input and caller.
 * Execution only reuses that verdict. Status and cancellation remain limited to runs this exact session started.
 * Inputs and results are never logged.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class HarnessWorkflowTools(
    private val toggles: FeatureToggles,
    private val library: Lazy<HarnessLibraryClient>,
    private val launches: Lazy<HarnessWorkflowLaunches>,
    private val runs: Lazy<HarnessRunsMachine>,
    private val services: HarnessWorkflowToolServices,
) : AgentToolContribution {
    private val log = Log.tag("HarnessTools")
    override val group: String = HarnessTools.GROUP
    override val title: String = "Харнессы"
    override val isDetachedSupported: Boolean = true
    override val catalog get() = HARNESS_WORKFLOW_SPECS.toolCatalog()

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (toggles.get(HarnessEnabled)) HARNESS_WORKFLOW_SPECS else emptyList()

    override suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval = when (val planned = snapshot(context, spec.name, arguments)) {
        is ReviewPlan.Ready -> planned.change?.let {
            harnessSafetySnapshot(context, planned.approval, it).approval
        } ?: planned.approval

        is ReviewPlan.Invalid -> AgentToolApproval(
            spec.name,
            spec.description,
            planned.reason,
            binding = "safety-refused",
        )
    }

    override suspend fun automaticAuthorization(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolAutomaticAuthorization = authorization(context, spec.name, arguments, canStart = true)

    private suspend fun authorization(
        context: AgentToolContext,
        name: String,
        arguments: JsonObject,
        canStart: Boolean,
        selected: Pair<Harness, HarnessItem.Workflow>? = null,
    ): AgentToolAutomaticAuthorization = when (val planned = snapshot(context, name, arguments, selected)) {
        is ReviewPlan.Invalid -> denied(planned.reason)

        is ReviewPlan.Ready -> planned.change?.let {
            reviewHarnessChange(services.reviews.value, context, planned.approval, it, canStart)
        } ?: AgentToolAutomaticAuthorization.Allowed(planned.approval)
    }

    private suspend fun snapshot(
        context: AgentToolContext,
        name: String,
        arguments: JsonObject,
        selected: Pair<Harness, HarnessItem.Workflow>? = null,
    ): ReviewPlan {
        if (!toggles.get(HarnessEnabled)) return ReviewPlan.Invalid("Harnesses are turned off")
        val approval = AgentToolApproval(name, name)
        val change = when (name) {
            HarnessTools.WORKFLOW_START -> {
                val (harness, workflow) = selected ?: target(arguments)
                    ?: return ReviewPlan.Invalid("No such enabled workflow; see harness_get")
                if (!isWorkflowInput(workflow.input, input(arguments))) return ReviewPlan.Invalid(INPUT_MISMATCH)
                buildJsonObject {
                    put("operation", name)
                    put("harness", Json.encodeToJsonElement(harness))
                    put("workflow", Json.encodeToJsonElement(workflow))
                    put("arguments", arguments)
                }.toString()
            }

            HarnessTools.WORKFLOW_CANCEL -> {
                val run = visible(context, arguments)
                    ?: return ReviewPlan.Invalid("No run with this id was started by this chat")
                if (run.status != WorkflowStatus.Running) return ReviewPlan.Invalid("The run has already finished")
                // Progress can advance while review runs. Identity, source, input and ownership never change.
                buildJsonObject {
                    put("operation", name)
                    put("run", run.id.value)
                    put("harness", run.harness.value)
                    put("workflow", run.workflow.value)
                    put("pinned", Json.encodeToJsonElement(run.pinned))
                    put("input", run.input)
                    put("caller", Json.encodeToJsonElement(run.caller))
                    put("startedAt", run.startedAt.toString())
                    put("deadline", run.deadline.toString())
                }.toString()
            }

            HarnessTools.WORKFLOW_STATUS -> return ReviewPlan.Ready(
                approval.copy(binding = "workflow-status"),
            )

            else -> return ReviewPlan.Invalid("Unknown tool")
        }
        return ReviewPlan.Ready(approval, change)
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        if (!toggles.get(HarnessEnabled)) return failure("Harnesses are turned off")
        if (name == HarnessTools.WORKFLOW_CANCEL) {
            authorization(context, name, arguments, canStart = false).failureReason(context)?.let {
                return failure(it)
            }
        }
        return when (name) {
            HarnessTools.WORKFLOW_START -> start(context, arguments)
            HarnessTools.WORKFLOW_STATUS -> status(context, arguments)
            HarnessTools.WORKFLOW_CANCEL -> cancel(context, arguments)
            else -> failure("Unknown tool")
        }
    }

    private suspend fun start(context: AgentToolContext, arguments: JsonObject): AgentToolResult {
        val selected = target(arguments) ?: return failure("No such enabled workflow; see harness_get")
        val (harness, workflow) = selected
        val approved = ApprovedWorkflow(harness.revision, workflow.id, harness)
        startRejection(context, arguments, selected, approved)?.let { return failure(it) }
        val request = checkNotNull(context.request)
        return try {
            val launch = launches.value.prepare(
                harness.id,
                workflow.name,
                input(arguments),
                context.session,
                context.workspace,
                RequestInitiator(context.session, request),
                services.origins.value.origin(context.session, request),
                WorkflowOrigin.Agent,
                // Only the snapshot the safety reviewer approved may start, even after the check above.
                approved,
            )
            val result = launches.value.start(launch)
            val wake = if (result.hasWake) {
                "This chat is woken when it finishes."
            } else {
                "Poll harness_workflow_status for the result."
            }
            AgentToolResult("Started run ${result.run.value}. $wake")
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            // The model's input was checked above; a host precondition failing here is not the model's input error.
            log.w(IllegalArgumentException(error::class.simpleName)) { "Workflow start refused: request mismatch" }
            failure("The workflow cannot start: the request no longer matches the workflow; check it with harness_get")
        } catch (error: IllegalStateException) {
            log.w(IllegalStateException(error::class.simpleName)) { "Workflow start refused" }
            failure("The workflow cannot start now: it is disabled, at capacity or no longer active here")
        }
    }

    /** Checks the captured candidate; later prepare/start retain the same exact reviewed snapshot. */
    private suspend fun startRejection(
        context: AgentToolContext,
        arguments: JsonObject,
        selected: Pair<Harness, HarnessItem.Workflow>,
        approved: ApprovedWorkflow,
    ): String? {
        val (harness, workflow) = selected
        val assessed = authorization(context, HarnessTools.WORKFLOW_START, arguments, false, selected)
        val denied = assessed.failureReason(context)
        return when {
            denied != null -> denied

            context.request == null -> "Workflows can only be started from an accepted turn"

            !services.helpers.value.canHost(context.session) -> "Helper chats cannot be hosted for this chat"

            !launches.value.isApproved(harness.id, workflow.name, approved) -> {
                log.i { "Workflow start refused: changed after safety review" }
                APPROVAL_CHANGED
            }

            !isWorkflowInput(workflow.input, input(arguments)) -> {
                log.d { "Workflow input rejected" }
                INPUT_MISMATCH
            }

            else -> null
        }
    }

    private fun AgentToolAutomaticAuthorization.failureReason(context: AgentToolContext): String? = when (this) {
        is AgentToolAutomaticAuthorization.Denied -> reason

        is AgentToolAutomaticAuthorization.Allowed -> APPROVAL_CHANGED.takeIf {
            context.authorization?.binding != approval.binding
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
                    HarnessRunsIntent.Internal.CancelRun(request, run.id, WorkflowViewer.Agent(context.session)),
                )
                if (sent == SendResult.Accepted) withTimeoutOrNull(OUTCOME_TIMEOUT) { receipt.await() } else null
            } finally {
                receipt.cancel()
            }
        }
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

            is HarnessRunsOutput.Started, is HarnessRunsOutput.RunFinished, null ->
                failure("Cancellation is not confirmed yet; check harness_workflow_status")
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

    private fun denied(reason: String) = AgentToolAutomaticAuthorization.Denied(reason)

    private sealed interface ReviewPlan {
        data class Invalid(val reason: String) : ReviewPlan
        data class Ready(val approval: AgentToolApproval, val change: String? = null) : ReviewPlan {
            override fun toString(): String = "ReviewPlan.Ready(***)"
        }
    }
}

/** Workflow side-effect services are lazy; status queries do not start helpers, reviews or request routing. */
@Inject
internal data class HarnessWorkflowToolServices(
    val helpers: Lazy<HelperAgents>,
    val origins: Lazy<HarnessRequestOrigins>,
    val reviews: Lazy<HarnessCodeReviews>,
)

/** Model text is never trusted as a run identity beyond its syntax; visibility is checked separately. */
private fun parseRunId(value: String): RunId? = if (value.matches(RUN_ID)) RunId(value) else null

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

private const val INPUT_MISMATCH = "The input does not match the workflow schema"
private const val APPROVAL_CHANGED = "The workflow changed after safety review; request it again"
private val OUTCOME_TIMEOUT = 5.seconds
private val ITEM_NAME = Regex("[a-z][a-z0-9_]{0,31}")
private val RUN_ID = Regex("wf_[a-zA-Z0-9_]{1,64}")
