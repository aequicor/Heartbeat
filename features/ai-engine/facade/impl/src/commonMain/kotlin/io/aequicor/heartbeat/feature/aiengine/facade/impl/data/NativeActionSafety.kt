package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionSafetyDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.SessionHooks
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety.ProposedAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Structured native preflight retains the same one-shot continuation and turn barrier as hosted execution. */
internal class NativeActionSafety(
    private val gate: ActionSafetyGate,
    private val permissions: ToolPermissions,
    private val hooks: SessionHooks,
    private val isEnabled: suspend (AgentToolContext, NativeToolCall) -> Boolean,
) {
    private val log = Log.tag("NativeActionSafety")

    suspend fun prepare(
        context: AgentToolContext,
        call: NativeToolCall,
        approval: AgentToolApproval,
    ): NativePreflight {
        // Capture before the first suspension: even Unknown evidence must bind the entire proposed call.
        val input = call.immutableSnapshot()
        val proposed = ProposedAction.native(context, input)
        while (true) {
            val mode = permissions.mode()
            val analysis = gate.native.analyze(context, input).snapshot()
            val hook = hook(context, input)
            val decision = decision(context, analysis, hook, mode, proposed)
            if (permissions.mode() != mode) continue
            if (call != input) return refused(INPUT_CHANGED)
            val captured = NativeActionSnapshot(mode, analysis, hook, input)
            val prepared = when (decision) {
                ActionSafetyDecision.Deny -> refused("The action is prohibited by policy")
                ActionSafetyDecision.Ask -> NativePreflight.Ask(approval, hook is ToolHookVerdict.Ask, captured)
                ActionSafetyDecision.Allow -> validated(context, call, captured, mode)
            }
            if (prepared != null) return prepared
        }
    }

    suspend fun confirm(
        context: AgentToolContext,
        call: NativeToolCall,
        checked: NativePreflight.Ask,
    ): NativePreflight {
        val captured = checkNotNull(checked.structured)
        while (true) {
            changed(context, call, captured)?.let { return refused(it) }
            val result = permissions.authorizeAction(context, checked.approval) { mode ->
                // The original Ask is stable. Do not retry a model until it happens to grant permission.
                if (mode == captured.mode) {
                    ActionSafetyDecision.Ask
                } else {
                    decision(
                        context,
                        captured.analysis,
                        captured.hook,
                        mode,
                        ProposedAction.native(context, captured.input),
                    )
                }
            }
            val prepared = when (result) {
                is ToolAuthorization.Denied -> refused(result.reason)
                is ToolAuthorization.Allowed -> validated(context, call, captured, result.mode)
            }
            if (prepared != null) return prepared
        }
    }

    private suspend fun validated(
        context: AgentToolContext,
        call: NativeToolCall,
        captured: NativeActionSnapshot,
        expectedMode: SecurityMode,
    ): NativePreflight? {
        val checked = permissions.validate(expectedMode) { ready(context, call, captured, expectedMode) }
        return when (checked) {
            is ModeValidation.Current -> checked.value
            ModeValidation.Changed -> null
        }
    }

    private suspend fun ready(
        context: AgentToolContext,
        call: NativeToolCall,
        captured: NativeActionSnapshot,
        expectedMode: SecurityMode,
    ): NativePreflight {
        val reason = changed(context, call, captured)
        return when {
            reason != null -> refused(reason)

            permissions.mode() != expectedMode -> refused("Native permission mode changed; request it again")

            !gate.isEnabled() || !isEnabled(context, captured.input) ->
                refused("Native action policy changed; request it again")

            call != captured.input -> refused(INPUT_CHANGED)

            else -> NativePreflight.Ready(NativeVerdict.Allow)
        }
    }

    private suspend fun decision(
        context: AgentToolContext,
        analysis: ActionAnalysis,
        hook: ToolHookVerdict,
        mode: SecurityMode,
        proposed: ProposedAction,
    ): ActionSafetyDecision = when {
        analysis is ActionAnalysis.Denied || hook is ToolHookVerdict.Deny -> ActionSafetyDecision.Deny
        hook is ToolHookVerdict.Ask -> ActionSafetyDecision.Ask
        else -> gate.evaluate(context, analysis, mode, proposed)
    }

    private suspend fun changed(
        context: AgentToolContext,
        call: NativeToolCall,
        captured: NativeActionSnapshot,
    ): String? {
        currentCoroutineContext().ensureActive()
        return when {
            call != captured.input -> INPUT_CHANGED

            !gate.isEnabled() || !isEnabled(context, captured.input) ->
                "Native action policy changed; request it again"

            else -> {
                val reason = if (gate.native.analyze(context, captured.input).snapshot() != captured.analysis) {
                    "Native action evidence changed; request it again"
                } else {
                    hookChange(hook(context, captured.input), captured.hook)
                }
                if (call != captured.input) INPUT_CHANGED else reason
            }
        }
    }

    private fun hookChange(latest: ToolHookVerdict, captured: ToolHookVerdict): String? = when {
        latest is ToolHookVerdict.Deny -> "The action is prohibited by a hook"

        latest is ToolHookVerdict.Ask && captured !is ToolHookVerdict.Ask ->
            "Native action constraints changed; request it again"

        else -> null
    }

    private suspend fun hook(context: AgentToolContext, call: NativeToolCall): ToolHookVerdict = try {
        hooks.context(context.session, context.request, context.turn)?.let {
            hooks.beforeTool(
                HookedToolCall(
                    it.copy(turn = null),
                    call.name,
                    call.action,
                    call.arguments ?: buildJsonObject {
                        put("command", call.command)
                        put("paths", JsonArray(call.paths.map(::JsonPrimitive)))
                    },
                    isNative = true,
                ),
            )
        } ?: ToolHookVerdict.Continue
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(IllegalStateException("Action hook failed (${error::class.simpleName.orEmpty()})")) {
            "Native action hook unavailable; confirmation required"
        }
        ToolHookVerdict.Ask("Action hook unavailable")
    }
}

/** Private immutable evidence of the original Ask; never accepted from the adapter or tool arguments. */
internal data class NativeActionSnapshot(
    val mode: SecurityMode,
    val analysis: ActionAnalysis,
    val hook: ToolHookVerdict,
    val input: NativeToolCall,
)

/** A separate JSON tree and collection storage prevent adapter mutations from changing retained evidence. */
private fun NativeToolCall.immutableSnapshot(): NativeToolCall = copy(
    paths = paths.toList(),
    arguments = arguments?.let { Json.parseToJsonElement(it.toString()).jsonObject },
    shell = shell?.let { it.copy(argumentPrefix = it.argumentPrefix.toList()) },
)

private fun refused(reason: String): NativePreflight = NativePreflight.Ready(NativeVerdict.Deny(reason))

private const val INPUT_CHANGED = "Native action input changed; request it again"
