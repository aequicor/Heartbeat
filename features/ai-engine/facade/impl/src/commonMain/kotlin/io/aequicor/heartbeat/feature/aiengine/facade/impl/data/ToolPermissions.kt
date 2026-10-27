package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.common.AlwaysFocused
import io.aequicor.heartbeat.core.common.WindowFocus
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionSafetyDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSecuritySettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.selects.select
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One permission decision for native and hosted execution. Pending prompts follow saved mode changes and are
 * declined once the window has been out of focus for [unattendedTimeout]: nobody is there to read them.
 */
internal class ToolPermissions(
    private val settings: AgentSecuritySettings,
    private val focus: WindowFocus = AlwaysFocused,
    private val unattendedTimeout: Duration = UNATTENDED_TIMEOUT,
    private val isTimeoutEnabled: suspend () -> Boolean = { false },
) {
    private val log = Log.tag("ToolPermissions")

    suspend fun mode(): SecurityMode = settings.modes.first()

    /** Watches mode changes throughout every suspending final check, rather than caching a read before it. */
    suspend fun <T> validate(mode: SecurityMode, block: suspend () -> T): ModeValidation<T> = coroutineScope {
        val changed = async(start = CoroutineStart.UNDISPATCHED) {
            settings.modes.first { it != mode }
            ModeValidation.Changed
        }
        val validated = async { ModeValidation.Current(block()) }
        try {
            select {
                // If both finished, an observed revocation wins over the completed validation.
                changed.onAwait { it }
                validated.onAwait { it }
            }
        } finally {
            changed.cancel()
            validated.cancel()
        }
    }

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
        val outcome = decide(context, approval) { it == captured }
        return when {
            mode() != captured -> null
            outcome is ApprovalOutcome.Approved && outcome.mode == captured -> ToolAuthorization.Allowed(captured)
            else -> ToolAuthorization.Denied(outcome.refusal)
        }
    }

    /** The accepted mode, or null for any refusal; use [decide] when the reason matters. */
    suspend fun authorize(
        context: AgentToolContext,
        approval: AgentToolApproval,
        needsConfirmation: suspend (SecurityMode) -> Boolean,
    ): SecurityMode? = (decide(context, approval, needsConfirmation) as? ApprovalOutcome.Approved)?.mode

    suspend fun decide(
        context: AgentToolContext,
        approval: AgentToolApproval,
        needsConfirmation: suspend (SecurityMode) -> Boolean,
    ): ApprovalOutcome {
        while (true) {
            val mode = mode()
            if (!needsConfirmation(mode)) {
                if (mode() == mode) return ApprovalOutcome.Approved(mode)
                continue
            }
            val outcome = coroutineScope {
                val changed = async {
                    settings.modes.first { it != mode }
                    null
                }
                val decision = async {
                    if (context.permissions.request(approval)) {
                        ApprovalOutcome.Approved(mode)
                    } else {
                        ApprovalOutcome.Declined
                    }
                }
                val unattended = async { awaitUnattended(approval) }
                try {
                    select<ApprovalOutcome?> {
                        changed.onAwait { it }
                        decision.onAwait { it }
                        unattended.onAwait { it }
                    }
                } finally {
                    changed.cancel()
                    decision.cancel()
                    unattended.cancel()
                }
            }
            if (outcome != null && mode() == mode) return outcome
        }
    }

    /** Declines after an uninterrupted [unattendedTimeout] without window focus; regaining focus restarts the wait. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitUnattended(approval: AgentToolApproval): ApprovalOutcome {
        if (!isTimeoutEnabled()) awaitCancellation()
        focus.isFocused.mapLatest { isFocused ->
            if (isFocused) {
                false
            } else {
                delay(unattendedTimeout)
                true
            }
        }.first { it }
        log.i { "Approval request for ${approval.name} expired: nobody attended for $unattendedTimeout" }
        return ApprovalOutcome.Unattended
    }

    private companion object {
        val UNATTENDED_TIMEOUT = 30.seconds
    }
}

/** What the user, or the lack of one, decided for a confirmation request. */
internal sealed interface ApprovalOutcome {
    /** Text the agent receives when the action is not approved. */
    val refusal: String get() = "The user declined this action"

    data class Approved(val mode: SecurityMode) : ApprovalOutcome
    data object Declined : ApprovalOutcome

    /** Nobody could answer: the window stayed out of focus. Not a refusal of the action itself. */
    data object Unattended : ApprovalOutcome {
        override val refusal: String =
            "No answer: the Heartbeat window was out of focus for 30 s; ask again when the user is back"
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

/** A mode transition invalidates the whole final snapshot, including an in-flight availability lookup. */
internal sealed interface ModeValidation<out T> {
    data class Current<T>(val value: T) : ModeValidation<T>
    data object Changed : ModeValidation<Nothing>
}
