package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.SessionHooks
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ToolPolicyResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Native permission decisions run inside the dispatcher's registered invocation and native lifetime. */
internal class NativeToolGate(
    private val policies: ToolPolicyResolver,
    private val hooks: SessionHooks,
    private val hostedNames: () -> Set<String>,
) {
    private val log = Log.tag("NativeToolGate")

    suspend fun prepare(context: AgentToolContext, call: NativeToolCall): NativePreflight = guarded {
        if (!isEnabled(context, call)) return@guarded denied("Native tool is disabled or its policy is unavailable")
        val hook = beforeTool(context, call)
        if (hook is ToolHookVerdict.Deny) {
            return@guarded denied("Blocked by a session hook: ${hook.reason.take(HOOK_CHARS)}")
        }
        val isApprovalRequired = hook is ToolHookVerdict.Ask || !call.covered(context.trust)
        currentCoroutineContext().ensureActive()
        if (!isEnabled(context, call)) return@guarded denied("Native tool became unavailable")
        if (!isApprovalRequired) return@guarded NativePreflight.Ready(NativeVerdict.Allow)
        approval(call, hook)
    }

    private suspend fun beforeTool(context: AgentToolContext, call: NativeToolCall): ToolHookVerdict {
        val hooked = hooks.context(context.session, context.request, context.turn)?.let { bound ->
            HookedToolCall(
                bound.copy(turn = null),
                call.name,
                call.action,
                call.arguments ?: buildJsonObject {
                    put("paths", JsonArray(call.paths.map(::JsonPrimitive)))
                    val command = call.command
                    if (command != null) put("command", command)
                },
                isNative = true,
            )
        }
        return hooked?.let { hooks.beforeTool(it) } ?: ToolHookVerdict.Continue
    }

    private fun approval(call: NativeToolCall, hook: ToolHookVerdict): NativePreflight {
        val details = call.arguments?.toString() ?: (listOfNotNull(call.command) + call.paths).joinToString("\n")
        if (details.length > APPROVAL_CHARS) return denied("Native action is too long to review in full")
        val reason = if (hook is ToolHookVerdict.Ask) {
            "A session hook requires confirmation: ${hook.reason.take(HOOK_CHARS)}\n\n"
        } else {
            ""
        }
        return NativePreflight.Ask(AgentToolApproval(call.name, "Native tool: ${call.name}", reason + details))
    }

    suspend fun confirm(context: AgentToolContext, call: NativeToolCall, approval: AgentToolApproval): NativeVerdict {
        val result = guarded {
            if (!isEnabled(context, call)) return@guarded denied("Native tool became unavailable")
            if (!context.permissions.request(approval)) return@guarded denied("The user declined this action")
            currentCoroutineContext().ensureActive()
            if (isEnabled(context, call)) {
                NativePreflight.Ready(NativeVerdict.Allow)
            } else {
                denied("Native tool became unavailable")
            }
        }
        return (result as NativePreflight.Ready).verdict
    }

    private suspend fun guarded(block: suspend () -> NativePreflight): NativePreflight = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(IllegalStateException("Native authorization failed (${e::class.simpleName.orEmpty()})")) {
            "Native tool authorization failed"
        }
        denied("Native tool authorization failed")
    }

    private suspend fun isEnabled(context: AgentToolContext, call: NativeToolCall): Boolean {
        val policy = policies.resolve(
            ToolPolicyScope(context.session.engine, context.workspace, context.session, context.target),
            hostedNames(),
            isExecuting = true,
        ) ?: return false
        return call.name !in policy.nativeOff && call.name in policy.nativeOn
    }
}

private const val HOOK_CHARS = 2_000
private const val APPROVAL_CHARS = 8_000

/** Private preflight state; only the dispatcher may bind an approval to a registered invocation. */
internal sealed interface NativePreflight {
    data class Ready(val verdict: NativeVerdict) : NativePreflight
    data class Ask(val approval: AgentToolApproval) : NativePreflight
}

private fun denied(reason: String): NativePreflight = NativePreflight.Ready(NativeVerdict.Deny(reason))
