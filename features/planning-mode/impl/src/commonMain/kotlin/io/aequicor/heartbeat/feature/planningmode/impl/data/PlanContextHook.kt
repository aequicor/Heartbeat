package io.aequicor.heartbeat.feature.planningmode.impl.data

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPromptAddition
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPromptReceipt
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanAuthor
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import io.aequicor.heartbeat.feature.planningmode.api.PlanStatus
import io.aequicor.heartbeat.feature.planningmode.api.PlanningState
import io.aequicor.heartbeat.feature.planningmode.api.PlanningTools
import io.aequicor.heartbeat.feature.planningmode.api.PlanningView
import io.aequicor.heartbeat.feature.planningmode.api.renderPlanBlock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Keeps the plan in the context of the session that serves its chat. A revision, mode and status reach the agent
 * once per native context revision: after a compaction or a new process the plan is sent again with the next
 * prompt, and without a provable revision it accompanies every prompt. Engines that rebuild their instructions every
 * turn read it from [PlanAgentTools] instead. When the user changes the plan during a turn, the next tool result
 * tells the agent to re-read it. Deliveries are remembered in memory only; after a restart the plan is sent again.
 */
internal class PlanContextHook(
    private val view: PlanningView,
    private val isEnabled: () -> Boolean,
    private val isRefreshed: (SessionRef) -> Boolean,
) : SessionHook {
    private val log = Log.tag("PlanContextHook")
    private val delivered = MutableStateFlow(emptyMap<SessionRef, Delivery>())
    private val known = MutableStateFlow(emptyMap<SessionRef, Long>())

    override val isIntercepting: Boolean get() = isEnabled()

    @HighFrequency
    override suspend fun preparePrompt(
        context: SessionHookContext,
        text: String,
        contextRevision: String?,
    ): SessionPromptAddition? {
        if (!isEnabled() || text.startsWith('/') || isRefreshed(context.session)) return null
        val plan = planOf(context.session) ?: return null
        val block = renderPlanBlock(plan) ?: return null
        val delivery = Delivery(contextRevision, plan.revision, plan.mode, plan.status)
        if (contextRevision != null && delivered.value[context.session] == delivery) return null
        log.v { "deliver the plan: revision=${plan.revision} mode=${plan.mode} status=${plan.status}" }
        return SessionPromptAddition(block, Receipt(context.session, delivery))
    }

    @HighFrequency
    override suspend fun afterTool(call: HookedToolCall, result: AgentToolResult): String? {
        if (!isEnabled()) return null
        val plan = planOf(call.context.session) ?: return null
        val session = call.context.session
        val last = known.value[session] ?: delivered.value[session]?.planRevision
        known.update { it + (session to plan.revision) }
        log.v { "plan revision known to the agent: ${last ?: "none"} -> ${plan.revision}" }
        val isReread = hostedName(call.name) == PlanningTools.READ
        val isChanged = last != null && plan.revision > last && plan.updatedBy != PlanAuthor.Agent
        return changedNote(plan.revision).takeIf { isChanged && !isReread }
    }

    private fun planOf(session: SessionRef): Plan? =
        (view.state.value as? PlanningState.Ready)?.planOf(session)?.takeIf { it.mode != PlanMode.Detached }

    private inner class Receipt(private val session: SessionRef, private val delivery: Delivery) :
        SessionPromptReceipt {
        override fun accepted(context: SessionHookContext, contextRevision: String?) {
            delivered.update { it + (session to delivery.copy(contextRevision = contextRevision)) }
            known.update { it + (session to delivery.planRevision) }
        }
    }

    private data class Delivery(
        val contextRevision: String?,
        val planRevision: Long,
        val mode: PlanMode,
        val status: PlanStatus,
    )

    private fun changedNote(revision: Long): String =
        "The user changed the plan of this chat (now revision $revision). Call ${PlanningTools.READ} before " +
            "relying on it or writing it."
}
