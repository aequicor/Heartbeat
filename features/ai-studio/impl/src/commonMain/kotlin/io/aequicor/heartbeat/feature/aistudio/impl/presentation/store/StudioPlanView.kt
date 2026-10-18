package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.PlanImplementation
import io.aequicor.heartbeat.feature.aistudio.api.PlanImplementationMode
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPlanning
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenAction
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanAuthorUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanImplementationModeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanStatusUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanUi
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanAuthor
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import io.aequicor.heartbeat.feature.planningmode.api.PlanStatus
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.PipelineContext

private typealias PlanPipeline = PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>

/**
 * Planning mode on the studio screen. Plans come from the planning feature, only for the chats shown in panes, so
 * remote frames stay small. Entering, leaving, approving and editing go to the planning feature; the mode of a new
 * chat and the implementation are studio machine commands. Plan texts are user data and never logged.
 */
internal class StudioPlanView(
    private val studio: MachineRef<AiStudioState, AiStudioIntent, AiStudioOutput>,
    private val planning: StudioPlanning,
) {
    private val log = Log.tag("StudioPlanView")

    suspend fun observe(pipeline: PlanPipeline): Unit = coroutineScope {
        launch { planning.availability.collect { pipeline.updateState { copy(isPlanningEnabled = it) } } }
        launch {
            val shown = studio.state
                .map { state -> (state as? AiStudioState.Ready)?.panes?.mapNotNull { it.sessionId }?.toSet().orEmpty() }
                .distinctUntilChanged()
            combine(planning.plans, shown) { plans, chats ->
                plans.filterKeys { it in chats }.mapNotNull { (id, plan) -> plan.toUi()?.let { id to it } }.toMap()
            }.distinctUntilChanged().collect { plans ->
                pipeline.updateState { copy(plans = plans.toImmutableMap()) }
            }
        }
    }

    suspend fun handle(pipeline: PlanPipeline, intent: AiStudioScreenIntent.Plan): Unit = with(pipeline) {
        when (intent) {
            is AiStudioScreenIntent.SelectPlanning -> {
                log.i { "planning mode of pane ${intent.paneId}: ${intent.isEnabled}" }
                sendTo(studio, AiStudioIntent.Public.SelectPlanning(intent.paneId, intent.isEnabled))
            }

            is AiStudioScreenIntent.SetPlanning -> {
                val chat = intent.sessionId
                val isApplied = if (intent.isEnabled) planning.enter(chat) else planning.leave(chat)
                outcome("planning mode of a chat set to ${intent.isEnabled}", isApplied)
            }

            is AiStudioScreenIntent.ApprovePlan ->
                outcome("plan approved", planning.approve(intent.sessionId, intent.revision))

            is AiStudioScreenIntent.SavePlan ->
                outcome("plan edit saved", planning.write(intent.sessionId, intent.text, intent.baseRevision))

            is AiStudioScreenIntent.ImplementPlan -> {
                log.i { "implement the plan: mode=${intent.mode} fresh=${intent.isFreshContext}" }
                val implementation = PlanImplementation(
                    intent.revision,
                    StudioSessionSettings(intent.modelId, intent.effort, intent.approval.toDomain()),
                    intent.prompt,
                    intent.isFreshContext,
                    intent.mode.toDomain(),
                )
                sendTo(studio, AiStudioIntent.Public.ImplementPlan(intent.sessionId, implementation))
            }
        }
    }

    /**
     * The planning feature refuses commands of a stale revision, a changed plan or a switched-off feature; the
     * screen then keeps showing the current plan from the feature, and has no notice for it yet.
     */
    private fun outcome(action: String, isApplied: Boolean) {
        if (isApplied) log.i { action } else log.w { "Refused: $action" }
    }
}

/** The plan as the screen shows it; a detached plan is not shown. */
internal fun Plan.toUi(): PlanUi? = PlanUi(
    revision = revision,
    isPlanning = mode == PlanMode.Planning,
    status = when (status) {
        PlanStatus.Draft -> PlanStatusUi.Draft
        PlanStatus.Proposed -> PlanStatusUi.Proposed
        PlanStatus.Approved -> PlanStatusUi.Approved
    },
    title = title,
    text = text,
    updatedBy = when (updatedBy) {
        PlanAuthor.Agent -> PlanAuthorUi.Agent
        PlanAuthor.User -> PlanAuthorUi.User
        PlanAuthor.External -> PlanAuthorUi.External
    },
).takeIf { mode != PlanMode.Detached }

private fun PlanImplementationModeUi.toDomain(): PlanImplementationMode = when (this) {
    PlanImplementationModeUi.Chat -> PlanImplementationMode.Chat
    PlanImplementationModeUi.Worktree -> PlanImplementationMode.Worktree
    PlanImplementationModeUi.Sandbox -> PlanImplementationMode.Sandbox
    PlanImplementationModeUi.Organism -> PlanImplementationMode.Organism
}
