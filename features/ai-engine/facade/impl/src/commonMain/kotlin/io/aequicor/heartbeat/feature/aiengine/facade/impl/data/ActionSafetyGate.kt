package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionSafetyDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.reviewModel
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety.ActionSafetyPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety.ProposedAction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared decision service. Availability, hooks, operation snapshots and lifecycle are enforced by the caller. */
internal class ActionSafetyGate(
    val native: NativeActionAnalysis,
    private val policy: ActionSafetyPolicy,
    private val enabled: suspend () -> Boolean,
) {
    private val lock = Mutex()
    private var isCapturedEnabled: Boolean? = null

    /** The feature flag is profile-lifetime immutable; changing it cannot leave an active native turn half gated. */
    suspend fun isEnabled(): Boolean = lock.withLock {
        isCapturedEnabled ?: enabled().also { isCapturedEnabled = it }
    }

    suspend fun evaluate(
        context: AgentToolContext,
        analysis: ActionAnalysis,
        mode: SecurityMode,
        proposed: ProposedAction,
    ): ActionSafetyDecision = when {
        analysis is ActionAnalysis.Denied -> ActionSafetyDecision.Deny
        mode == SecurityMode.Trust -> ActionSafetyDecision.Allow
        mode == SecurityMode.Ask -> ActionSafetyDecision.Ask
        else -> policy.evaluate(analysis, context.userInstruction, context.reviewModel, proposed)
    }
}
