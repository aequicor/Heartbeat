package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveModels
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelChoice
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SelectionContext
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentComplexity
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentHost
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentNeed
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPriority
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentRequest
import io.aequicor.heartbeat.feature.harness.api.workflow.AdaptiveAgent
import io.aequicor.heartbeat.feature.harness.api.workflow.AgentComplexity
import io.aequicor.heartbeat.feature.harness.api.workflow.AgentNeed
import io.aequicor.heartbeat.feature.harness.api.workflow.AgentPriority
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperKind
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperModel
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperModelRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperModels

/**
 * Harness helpers on the profile's adaptive subagents. The parent's model is unknown to the harness, so selection
 * runs without it and inherits when cross-engine selection is forbidden; the helper host still reviews the helper's
 * actions on its parent's model.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class AdaptiveHarnessHelperModels(private val models: Lazy<AdaptiveModels>) : HarnessHelperModels {
    private val log = Log.tag("HarnessHelperModels")

    override suspend fun choose(request: HarnessHelperModelRequest): HarnessHelperModel? {
        val host = when (request.kind) {
            HarnessHelperKind.Workflow -> SubagentHost.WorkflowHelper
            HarnessHelperKind.Script -> SubagentHost.ScriptHelper
        }
        val context = SelectionContext(host, parentTarget = null, request.workspace)
        return when (val choice = models.value.select(request.adaptive.toRequest(request.task), context)) {
            is ModelChoice.Selected -> {
                log.i { "Adaptive ${request.kind} helper configured: source=${choice.source}" }
                HarnessHelperModel(choice.target, choice.reasoningEffort)
            }

            is ModelChoice.Inherit -> {
                log.d { "Adaptive ${request.kind} helper inherits: ${choice.reason}" }
                null
            }
        }
    }
}

private fun AdaptiveAgent.toRequest(task: String) = SubagentRequest(
    task,
    priority?.let { priority ->
        when (priority) {
            AgentPriority.Quality -> SubagentPriority.Quality
            AgentPriority.Balanced -> SubagentPriority.Balanced
            AgentPriority.Speed -> SubagentPriority.Speed
            AgentPriority.Cost -> SubagentPriority.Cost
        }
    },
    when (complexity) {
        AgentComplexity.Low -> SubagentComplexity.Low
        AgentComplexity.Medium -> SubagentComplexity.Medium
        AgentComplexity.High -> SubagentComplexity.High
    },
    needs.mapTo(mutableSetOf()) { need ->
        when (need) {
            AgentNeed.Images -> SubagentNeed.Images
            AgentNeed.Pdf -> SubagentNeed.Pdf
            AgentNeed.LongContext -> SubagentNeed.LongContext
        }
    },
    tags.mapTo(mutableSetOf(), String::lowercase),
)
