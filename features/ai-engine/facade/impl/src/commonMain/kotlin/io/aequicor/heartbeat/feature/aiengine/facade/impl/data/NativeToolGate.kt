package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
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

/** Native calls use exactly the same profile permission decision as hosted calls. */
internal class NativeToolGate(
    private val policies: ToolPolicyResolver,
    private val hooks: SessionHooks,
    private val permissions: ToolPermissions,
    private val hostedNames: () -> Set<String>,
    private val isVirtual: suspend (AgentToolContext) -> Boolean = { false },
    private val actionSafety: ActionSafetyGate? = null,
) {
    private val log = Log.tag("NativeToolGate")

    suspend fun prepare(context: AgentToolContext, call: NativeToolCall): NativePreflight = guarded {
        if (isVirtual(
                context,
            )
        ) {
            return@guarded denied("Native tools are unavailable in a virtual workspace; use hosted tools")
        }
        if (!isEnabled(
                context,
                call,
            )
        ) {
            return@guarded denied("Native tool is not available in this engine configuration")
        }
        prepareCurrent(context, call)
    }

    private suspend fun prepareCurrent(context: AgentToolContext, call: NativeToolCall): NativePreflight {
        if (actionSafety?.isEnabled() == true) {
            val structured = NativeActionSafety(actionSafety, permissions, hooks, ::isEnabled)
            return structured.prepare(context, call, approval(call))
        }
        while (true) {
            val mode = permissions.mode()
            val isHookConfirmation = mode != SecurityMode.Trust && hookNeedsConfirmation(context, call)
            val isConfirmation = isHookConfirmation || needsConfirmation(mode, call.action, isOrdinaryEdit = false)
            if (permissions.mode() == mode) {
                return if (isConfirmation) {
                    NativePreflight.Ask(
                        approval(call),
                        isHookConfirmation,
                    )
                } else {
                    NativePreflight.Ready(NativeVerdict.Allow)
                }
            }
        }
    }

    private suspend fun hookNeedsConfirmation(context: AgentToolContext, call: NativeToolCall): Boolean = try {
        val bound = hooks.context(context.session, context.request, context.turn)
        val hook = bound?.let {
            hooks.beforeTool(
                HookedToolCall(
                    it.copy(
                        turn = null,
                    ),
                    call.name,
                    call.action,
                    call.arguments ?: buildJsonObject {
                        put("paths", JsonArray(call.paths.map(::JsonPrimitive)))
                        put("command", call.command)
                    },
                    isNative = true,
                ),
            )
        } ?: ToolHookVerdict.Continue
        hook != ToolHookVerdict.Continue
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(IllegalStateException("Native hook failed (${error::class.simpleName.orEmpty()})")) {
            "Native permission hook unavailable; requesting confirmation"
        }
        true
    }

    private fun approval(call: NativeToolCall): AgentToolApproval = AgentToolApproval(
        call.name,
        "Native tool: ${call.name}",
        buildJsonObject {
            call.arguments?.let { put("arguments", it) }
            put("command", call.command)
            put("paths", JsonArray(call.paths.map(::JsonPrimitive)))
            put("workingDirectory", call.workingDirectory)
        }.toString(),
    )

    suspend fun confirm(context: AgentToolContext, call: NativeToolCall, checked: NativePreflight.Ask): NativeVerdict {
        val result = guarded {
            if (checked.structured != null && actionSafety != null) {
                NativeActionSafety(actionSafety, permissions, hooks, ::isEnabled).confirm(context, call, checked)
            } else {
                confirmCurrent(context, call, checked)
            }
        }
        return (result as NativePreflight.Ready).verdict
    }

    private suspend fun confirmCurrent(
        context: AgentToolContext,
        call: NativeToolCall,
        checked: NativePreflight.Ask,
    ): NativePreflight {
        var outcome: NativePreflight? = null
        while (outcome == null) {
            outcome = confirmAttempt(context, call, checked)
        }
        return outcome
    }

    private suspend fun confirmAttempt(
        context: AgentToolContext,
        call: NativeToolCall,
        checked: NativePreflight.Ask,
    ): NativePreflight? {
        if (!isEnabled(context, call)) return denied("Native tool became unavailable")
        val accepted = permissions.authorize(context, checked.approval) {
            it != SecurityMode.Trust &&
                (checked.isHookConfirmation || needsConfirmation(it, call.action, isOrdinaryEdit = false))
        }
        currentCoroutineContext().ensureActive()
        return when {
            accepted == null -> denied("The user declined this action")
            approval(call) != checked.approval -> denied("Native input changed while awaiting confirmation")
            !isEnabled(context, call) -> denied("Native tool became unavailable")
            permissions.mode() == accepted -> NativePreflight.Ready(NativeVerdict.Allow)
            else -> null
        }
    }

    private suspend fun guarded(block: suspend () -> NativePreflight): NativePreflight = try {
        block().also {
            log.i { "Native permission outcome=${if (it is NativePreflight.Ask) "ask" else "resolved"}" }
        }
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
        )
        return policy != null && call.name in policy.nativeOn && call.name !in policy.nativeOff
    }
}

/** Private preflight state; only the dispatcher may bind an approval to a registered invocation. */
internal sealed interface NativePreflight {
    data class Ready(val verdict: NativeVerdict) : NativePreflight
    data class Ask(
        val approval: AgentToolApproval,
        val isHookConfirmation: Boolean,
        val structured: NativeActionSnapshot? = null,
    ) : NativePreflight
}

private fun denied(reason: String): NativePreflight = NativePreflight.Ready(NativeVerdict.Deny(reason))
