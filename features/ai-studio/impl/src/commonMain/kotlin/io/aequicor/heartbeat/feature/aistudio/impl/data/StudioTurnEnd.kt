package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.stripHostDirectives
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueMode
import io.aequicor.heartbeat.feature.autocontinue.api.AutopilotContextSettings
import io.aequicor.heartbeat.feature.autocontinue.api.ChatAuto
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationContext
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationRequest
import io.aequicor.heartbeat.feature.autocontinue.api.DialogAuthor
import io.aequicor.heartbeat.feature.autocontinue.api.Phase
import io.aequicor.heartbeat.feature.autocontinue.api.dialogOf
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.HANDOFF_REQUEST_PREFIX
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import io.aequicor.heartbeat.feature.planningmode.api.PlanStatus

/** How auto-continue sees a turn of a chat. */
internal enum class StudioTurnKind {
    /** The human's own message or click: it takes over from the autopilot. */
    Human,

    /** A host-started turn of the agent (a wake, a continuation, an autopilot reply). */
    Host,

    /** Context upkeep (compaction, rollover, the handoff summary before it): the dialog goes on as it was. */
    Service,
}

/**
 * Whether context upkeep that just ended with [outcome] is a reason to judge the chat ([chat] as auto-continue sees
 * it) again: only a completed one, for a chat that paused for it or has nothing on offer while on. A stopped or failed
 * compaction leaves the chat to the human, a handoff summary is followed by a rollover instead, and replies on offer,
 * a countdown or a halt stay as they are.
 */
internal fun isRejudgedAfterService(request: StudioTurnRequest, outcome: RunOutcome, chat: ChatAuto?): Boolean = when {
    chat == null || outcome != RunOutcome.Completed -> false
    request.request.value.startsWith(HANDOFF_REQUEST_PREFIX) -> false
    chat.phase == Phase.Paused -> true
    else -> chat.phase == Phase.Idle && chat.mode != AutoContinueMode.Off
}

/** The kind of turn [this] request starts. */
internal fun StudioTurnRequest.turnKind(): StudioTurnKind = when {
    compaction != null || isRollover || request.value.startsWith(HANDOFF_REQUEST_PREFIX) -> StudioTurnKind.Service
    isUserPrompt || userInstruction != null -> StudioTurnKind.Human
    else -> StudioTurnKind.Host
}

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
        get() = isChecklistOpen || isCompacting || hasQuestions ||
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
    settings: AutopilotContextSettings = AutopilotContextSettings(),
    request: ContinuationRequest? = null,
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
    if (dialog.none { it.author == DialogAuthor.Agent } && request == null) return null
    val required = setOf(
        dialog.indexOfFirst { it.author == DialogAuthor.Human },
        dialog.indexOfLast { it.author == DialogAuthor.Human },
        dialog.indexOfLast { it.author == DialogAuthor.Agent },
    )
    val kept = dialog.filterIndexed { index, _ ->
        index in required || (settings.history && index >= dialog.size - settings.historyMessages)
    }
    return ContinuationContext(
        route,
        kept,
        planNote(plan).takeIf { settings.plan },
        request,
        extraContext(items, settings),
    )
}

/** The plan for the continuer: mode, status and text; null outside the planning mode. */
internal fun planNote(plan: Plan?): String? {
    if (plan == null || plan.mode == PlanMode.Detached) return null
    val mode = plan.mode.name.lowercase()
    val status = plan.status.name.lowercase()
    return "Planning mode: $mode, plan status: $status. Only the human approves or implements a plan.\n\n${plan.text}"
}

/** Optional completed calls are grouped by source, independently of conversational history. */
private fun extraContext(items: List<SessionItem>, settings: AutopilotContextSettings): String? {
    val calls = items.filterIsInstance<SessionItem.ToolCall>().associateBy { it.call }
    val blocks = items.filterIsInstance<SessionItem.ToolResult>().takeLast(MAX_CONTEXT_RESULTS).mapNotNull { result ->
        calls[result.call]?.let { call -> toolContext(call, result, settings) }
    }
    val plan = items.filterIsInstance<SessionItem.Plan>().lastOrNull()?.takeIf { settings.plan }
    return (blocks + listOfNotNull(plan?.let { "Agent plan: ${it.steps}" })).joinToString("\n").ifBlank { null }
}

private fun AutopilotContextSettings.includesResult(name: String): Boolean = when (name) {
    "questionnaire_ask" -> questionnaires
    "subagent_receive", "scheduler_start_action", "scheduler_list", "scheduler_events" -> backgroundResults
    else -> toolResults
}

private fun toolContext(
    call: SessionItem.ToolCall,
    result: SessionItem.ToolResult,
    settings: AutopilotContextSettings,
): String? {
    val isIncluded = settings.includesResult(call.name)
    if (!isIncluded && !settings.toolArguments) return null
    return buildString {
        appendLine("Tool: ${call.name}")
        if (settings.toolArguments) appendLine("Arguments: ${call.arguments.take(CONTEXT_ARGUMENT_CHARS)}")
        if (isIncluded) {
            appendLine(
                result.parts.filterIsInstance<ContentPart.Text>().joinToString(
                    "\n",
                ) { it.text }.take(CONTEXT_RESULT_CHARS),
            )
            result.failure?.let { appendLine("Failure: ${it::class.simpleName.orEmpty()}") }
        }
    }
}

private const val MAX_CONTEXT_RESULTS = 40
private const val CONTEXT_ARGUMENT_CHARS = 2000
private const val CONTEXT_RESULT_CHARS = 4000
