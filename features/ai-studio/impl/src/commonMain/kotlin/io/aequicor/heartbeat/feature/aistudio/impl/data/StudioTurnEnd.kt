package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.stripHostDirectives
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationContext
import io.aequicor.heartbeat.feature.autocontinue.api.DialogAuthor
import io.aequicor.heartbeat.feature.autocontinue.api.dialogOf
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import io.aequicor.heartbeat.feature.planningmode.api.PlanStatus

/**
 * What else waits for the human in a chat whose turn completed: an open checklist card, a proposed plan, a wake of
 * the agent itself (`scheduler_sleep`, `subagent_receive`, a background action), a compaction or an open questionnaire.
 */
internal data class StudioTurnEnd(
    val isChecklistOpen: Boolean = false,
    val plan: Plan? = null,
    val hasWakes: Boolean = false,
    val isCompacting: Boolean = false,
    val hasQuestions: Boolean = false,
) {
    /** True when the continuer must not answer for the human now. */
    val isAwaitingHuman: Boolean
        get() = isChecklistOpen || hasWakes || isCompacting || hasQuestions ||
            (plan?.mode == PlanMode.Planning && plan.status == PlanStatus.Proposed)
}

/**
 * What the continuer reads about [record] after a turn: its route, the dialog of [items] with host directives removed
 * from user messages and the messages of [autopilotTurns] marked as autopilot, and the plan of the planning mode.
 * Null without a route or without any agent reply to continue from.
 */
internal fun continuationContext(
    record: StudioChatRecord,
    items: List<SessionItem>,
    autopilotTurns: Set<String>,
    plan: Plan?,
): ContinuationContext? {
    val route = record.target ?: return null
    val autopilotItems = items.filter { it.info.turn?.value in autopilotTurns }.mapTo(mutableSetOf()) { it.info.id }
    val dialog = dialogOf(items, autopilotItems).mapNotNull { entry ->
        if (entry.author == DialogAuthor.Agent) {
            entry
        } else {
            stripHostDirectives(entry.text).trim().takeIf { it.isNotEmpty() }?.let { entry.copy(text = it) }
        }
    }
    if (dialog.none { it.author == DialogAuthor.Agent }) return null
    return ContinuationContext(route, dialog, planNote(plan))
}

/** The plan for the continuer: mode, status and text; null outside the planning mode. */
internal fun planNote(plan: Plan?): String? {
    if (plan == null || plan.mode == PlanMode.Detached) return null
    val mode = plan.mode.name.lowercase()
    val status = plan.status.name.lowercase()
    return "Planning mode: $mode, plan status: $status. Only the human approves or implements a plan.\n\n${plan.text}"
}
