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

    suspend fun authorize(context: AgentToolContext, call: NativeToolCall): NativeVerdict = try {
        if (!isEnabled(context, call)) {
            NativeVerdict.Deny("Native tool is disabled or its policy is unavailable")
        } else {
            authorizeEnabled(context, call)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(IllegalStateException("Native authorization failed (${e::class.simpleName.orEmpty()})")) {
            "Native tool authorization failed"
        }
        NativeVerdict.Deny("Native tool authorization failed")
    }

    private suspend fun authorizeEnabled(context: AgentToolContext, call: NativeToolCall): NativeVerdict {
        val hooked = hooks.context(context.session, context.request, context.turn)?.let { bound ->
            HookedToolCall(
                bound.copy(turn = null),
                call.name,
                call.action,
                buildJsonObject {
                    put("paths", JsonArray(call.paths.map(::JsonPrimitive)))
                    val command = call.command
                    if (command != null) put("command", command)
                },
                isNative = true,
            )
        }
        val hook = hooked?.let { hooks.beforeTool(it) } ?: ToolHookVerdict.Continue
        val refusal = when {
            hook is ToolHookVerdict.Deny ->
                NativeVerdict.Deny("Blocked by a session hook: ${hook.reason.take(HOOK_CHARS)}")

            hook is ToolHookVerdict.Ask || !call.covered(context.trust) -> request(context, call, hook)

            else -> null
        }
        if (refusal != null) return refusal
        currentCoroutineContext().ensureActive()
        return if (isEnabled(context, call)) {
            NativeVerdict.Allow
        } else {
            NativeVerdict.Deny("Native tool became unavailable")
        }
    }

    private suspend fun request(
        context: AgentToolContext,
        call: NativeToolCall,
        hook: ToolHookVerdict,
    ): NativeVerdict.Deny? {
        val details = (listOfNotNull(call.command) + call.paths).joinToString("\n")
        if (details.length > APPROVAL_CHARS) return NativeVerdict.Deny("Native action is too long to review in full")
        val reason = if (hook is ToolHookVerdict.Ask) {
            "A session hook requires confirmation: ${hook.reason.take(HOOK_CHARS)}\n\n"
        } else {
            ""
        }
        val approval = AgentToolApproval(call.name, "Native tool: ${call.name}", reason + details)
        return if (context.permissions.request(approval)) null else NativeVerdict.Deny("The user declined this action")
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
