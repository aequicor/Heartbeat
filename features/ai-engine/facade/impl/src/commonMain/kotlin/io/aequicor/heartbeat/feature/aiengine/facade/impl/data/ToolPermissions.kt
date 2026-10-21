package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionSafetyDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSecuritySettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.selects.select

/** One permission decision for native and hosted execution. Pending prompts follow saved mode changes. */
internal class ToolPermissions(private val settings: AgentSecuritySettings) {
    suspend fun mode(): SecurityMode = settings.modes.first()

    /** A hard refusal never opens an approval dialog and cannot be changed by the model or an Allow click. */
    suspend fun authorizeAction(
        context: AgentToolContext,
        approval: AgentToolApproval,
        evaluate: suspend (SecurityMode) -> ActionSafetyDecision,
    ): ToolAuthorization {
        while (true) {
            val captured = mode()
            val decision = evaluate(captured)
            if (mode() != captured) continue
            val authorization = when (decision) {
                ActionSafetyDecision.Deny -> ToolAuthorization.Denied("The action is prohibited by policy")
                ActionSafetyDecision.Allow -> ToolAuthorization.Allowed(captured)
                ActionSafetyDecision.Ask -> confirmAction(context, approval, captured)
            }
            if (authorization != null) return authorization
        }
    }

    private suspend fun confirmAction(
        context: AgentToolContext,
        approval: AgentToolApproval,
        captured: SecurityMode,
    ): ToolAuthorization? {
        val accepted = authorize(context, approval) { it == captured }
        return when {
            mode() != captured -> null
            accepted == captured -> ToolAuthorization.Allowed(captured)
            else -> ToolAuthorization.Denied("The user declined this action")
        }
    }

    suspend fun authorize(
        context: AgentToolContext,
        approval: AgentToolApproval,
        needsConfirmation: suspend (SecurityMode) -> Boolean,
    ): SecurityMode? {
        while (true) {
            val mode = mode()
            if (!needsConfirmation(mode)) {
                if (mode() == mode) return mode
                continue
            }
            val isApproved = coroutineScope {
                val changed = async {
                    settings.modes.first { it != mode }
                    null
                }
                val decision = async { context.permissions.request(approval) }
                try {
                    select<Boolean?> {
                        changed.onAwait { it }
                        decision.onAwait { it }
                    }
                } finally {
                    changed.cancel()
                    decision.cancel()
                }
            }
            if (isApproved != null && mode() == mode) return mode.takeIf { isApproved }
        }
    }
}

/** Exact profile mode used for one decision; callers revalidate it after their operation snapshot. */
internal sealed interface ToolAuthorization {
    data class Allowed(val mode: SecurityMode) : ToolAuthorization
    data class Denied(val reason: String) : ToolAuthorization
}

/** Constructor fallback for isolated tests; the production binding always supplies profile storage. */
internal object DefaultSecuritySettings : AgentSecuritySettings {
    override val modes = flow {
        emit(SecurityMode.Automatic)
        awaitCancellation()
    }
    override suspend fun load(): SecurityMode = SecurityMode.Automatic
    override suspend fun save(mode: SecurityMode): Unit = error("A profile security store is required")
}

/** Unknown effects require confirmation. Tool names alone never prove that an arbitrary shell command is safe. */
internal fun needsConfirmation(mode: SecurityMode, action: AgentToolAction, isOrdinaryEdit: Boolean): Boolean =
    when (mode) {
        SecurityMode.Trust -> false
        SecurityMode.Ask -> action != AgentToolAction.Read
        SecurityMode.Automatic -> action != AgentToolAction.Read && !(action == AgentToolAction.Edit && isOrdinaryEdit)
    }
