package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReview
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReviewer
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.scheduler.impl.data.SchedulerSafety

/** A reviewer that records every change it is asked about and answers [verdict], or fails with [failure]. */
internal class RecordingReviewer(
    var verdict: AgentSafetyReview = AgentSafetyReview("safe", "Bounded and inside the project"),
    var failure: Exception? = null,
) : AgentSafetyReviewer {
    val changes = mutableListOf<String>()

    override suspend fun review(target: EngineTarget, policy: String, change: String): AgentSafetyReview {
        changes += change
        failure?.let { throw it }
        return verdict
    }
}

internal fun safetyOf(reviewer: AgentSafetyReviewer = RecordingReviewer()) = SchedulerSafety(reviewer)

/** The studio's default: an independent review replaces the user's decision. */
internal fun AgentToolContext.automatic(call: String = "call") =
    copy(callId = ToolCallId(call), decisionMode = AgentToolDecisionMode.AutomaticSafety)
