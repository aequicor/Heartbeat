package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionSafetyDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.SessionHooks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** All hosted owners, including dynamic/MCP contributions, enter the same evidence and permission pipeline. */
internal class HostedActionSafety(
    private val gate: ActionSafetyGate,
    private val permissions: ToolPermissions,
    private val hooks: SessionHooks,
) {
    private val log = Log.tag("HostedActionSafety")

    suspend fun refusal(
        context: AgentToolContext,
        operation: HostedApproval,
        isInputCurrent: () -> Boolean,
        revalidate: suspend () -> String?,
    ): String? {
        val analysis = operation.owner.analyze(context, operation.spec, operation.arguments).snapshot()
        val hook = hook(context, operation)
        val approval = operation.approval.copy(
            description = listOfNotNull(
                operation.approval.description,
                (analysis as? ActionAnalysis.Unknown)?.reason,
                "Arguments: ${operation.argumentsSnapshot}",
            ).joinToString("\n\n"),
        )
        while (true) {
            val accepted = permissions.authorizeAction(context, approval) { mode ->
                decision(context, operation, analysis, hook, mode)
            }
            val failure = when (accepted) {
                is ToolAuthorization.Denied -> accepted.reason

                is ToolAuthorization.Allowed -> {
                    currentCoroutineContext().ensureActive()
                    revalidate() ?: changed(context, operation, analysis, hook)
                }
            }
            if (failure != null) return failure
            val mode = permissions.mode()
            // Analyzer, hook and mode reads above may suspend after the earlier operation revalidation.
            val inputFailure = if (isInputCurrent()) null else HOSTED_INPUT_CHANGED
            if (inputFailure != null || mode == (accepted as ToolAuthorization.Allowed).mode) return inputFailure
        }
    }

    private suspend fun decision(
        context: AgentToolContext,
        operation: HostedApproval,
        analysis: ActionAnalysis,
        hook: ToolHookVerdict,
        mode: SecurityMode,
    ): ActionSafetyDecision {
        val existing = operation.owner.existingAuthorization(context, operation.spec, operation.arguments)
        return when {
            analysis is ActionAnalysis.Denied || hook is ToolHookVerdict.Deny -> ActionSafetyDecision.Deny
            hook is ToolHookVerdict.Ask -> ActionSafetyDecision.Ask
            !existing?.binding.isNullOrBlank() && existing == operation.approval -> ActionSafetyDecision.Allow
            operation.owner.requiresDecision(context, operation.spec, operation.arguments) -> ActionSafetyDecision.Ask
            else -> gate.evaluate(context, analysis, mode)
        }
    }

    private suspend fun changed(
        context: AgentToolContext,
        operation: HostedApproval,
        analysis: ActionAnalysis,
        capturedHook: ToolHookVerdict,
    ): String? {
        if (!gate.isEnabled()) return "Action policy changed; request the operation again"
        val latest = operation.owner.analyze(context, operation.spec, operation.arguments).snapshot()
        return if (latest != analysis) {
            "Action evidence changed; request the operation again"
        } else {
            hookChange(hook(context, operation), capturedHook)
        }
    }

    private fun hookChange(latest: ToolHookVerdict, captured: ToolHookVerdict): String? = when {
        latest is ToolHookVerdict.Deny -> "The action is prohibited by a hook"

        latest is ToolHookVerdict.Ask && captured !is ToolHookVerdict.Ask ->
            "Action constraints changed; request the operation again"

        else -> null
    }

    private suspend fun hook(context: AgentToolContext, operation: HostedApproval): ToolHookVerdict = try {
        hooks.context(context.session, context.request, context.turn)?.let {
            hooks.beforeTool(HookedToolCall(it, operation.spec.name, operation.spec.action, operation.arguments))
        } ?: ToolHookVerdict.Continue
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(IllegalStateException("Action hook failed (${error::class.simpleName.orEmpty()})")) {
            "Action hook unavailable; confirmation required"
        }
        ToolHookVerdict.Ask("Action hook unavailable")
    }
}

/** Detach mutable collection implementations supplied by owners from the decision captured before suspension. */
internal fun ActionAnalysis.snapshot(): ActionAnalysis = when (this) {
    is ActionAnalysis.Known -> copy(
        evidence = evidence.copy(resources = evidence.resources.toList(), effects = evidence.effects.toSet()),
    )

    is ActionAnalysis.Unknown, is ActionAnalysis.Denied -> this
}
