package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReviewer
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.reviewModel
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
    private val safety: Lazy<AgentSafetyReviewer>,
    private val commandEvidence: Set<CommandSafetyEvidence>,
    private val hostedNames: () -> Set<String>,
    private val isVirtual: suspend (AgentToolContext) -> Boolean = { false },
) {
    private val log = Log.tag("NativeToolGate")

    suspend fun prepare(context: AgentToolContext, call: NativeToolCall): NativePreflight {
        // What decided the outcome, for the one central log line: policy, hook, trust or error.
        var source = "policy"
        val result = guarded(onFailure = { source = "error" }) { preflight(context, call) { source = it } }
        log.i {
            "Native tool preflight name=${call.name} action=${call.action} outcome=${result.outcome()} source=$source"
        }
        return result
    }

    /** Reports to [decidedBy] the source of the check that decides the outcome, as each check begins. */
    private suspend fun preflight(
        context: AgentToolContext,
        call: NativeToolCall,
        decidedBy: (String) -> Unit,
    ): NativePreflight {
        if (isVirtual(context)) return denied("Native tools are unavailable in a virtual workspace; use hosted tools")
        val policy = policy(context)
        if (!policy.allows(call)) return denied("Native tool is disabled or its policy is unavailable")
        decidedBy("hook")
        val hook = beforeTool(context, call)
        if (context.decisionMode == AgentToolDecisionMode.AutomaticSafety) {
            decidedBy("safety")
            return automatic(context, call, hook, checkNotNull(policy))
        }
        if (hook !is ToolHookVerdict.Deny) {
            currentCoroutineContext().ensureActive()
            decidedBy("policy")
        }
        return when {
            hook is ToolHookVerdict.Deny -> denied("Blocked by a session hook: ${hook.reason.take(HOOK_CHARS)}")

            !isEnabled(context, call) -> denied("Native tool became unavailable")

            hook is ToolHookVerdict.Ask -> approval(call, hook).also { decidedBy("hook") }

            else -> {
                decidedBy("trust")
                if (call.covered(context.trust)) NativePreflight.Ready(NativeVerdict.Allow) else approval(call, hook)
            }
        }
    }

    private suspend fun automatic(
        context: AgentToolContext,
        call: NativeToolCall,
        hook: ToolHookVerdict,
        reviewedPolicy: ResolvedToolPolicy,
    ): NativePreflight {
        val restriction = hook.automaticRefusal() ?: call.automaticDenial
            ?: "Automatic edits require a trusted workspace".takeIf {
                call.action == AgentToolAction.Edit && context.workspace == null
            }
        if (restriction != null) return denied(restriction)
        val target = context.reviewModel ?: return denied("Native safety review has no trusted model target")
        val snapshot = snapshot(context, call)
            ?: return denied("Native action has no complete bounded safety snapshot")
        val review = safety.value.review(target, NATIVE_SAFETY_POLICY, snapshot)
        currentCoroutineContext().ensureActive()
        return if (!review.isSafe) {
            denied("Native safety review refused: ${review.reason.take(HOOK_CHARS)}")
        } else {
            recheckAutomatic(context, call, reviewedPolicy, snapshot)
        }
    }

    /** Review suspension cannot preserve a grant across changed hooks, policy or invocation input. */
    private suspend fun recheckAutomatic(
        context: AgentToolContext,
        call: NativeToolCall,
        reviewedPolicy: ResolvedToolPolicy,
        snapshot: String,
    ): NativePreflight {
        val currentHook = beforeTool(context, call)
        currentHook.automaticRefusal()?.let { return denied(it) }
        val currentPolicy = policy(context)
        currentCoroutineContext().ensureActive()
        return if (!currentPolicy.allows(call) || currentPolicy != reviewedPolicy ||
            snapshot != snapshot(context, call)
        ) {
            denied("Native action or policy changed during safety review; request it again")
        } else {
            NativePreflight.Ready(NativeVerdict.Allow)
        }
    }

    private suspend fun snapshot(context: AgentToolContext, call: NativeToolCall): String? {
        val directory = call.workingDirectory
        val command = call.command
        val environment = call.commandEnvironment
        if (call.action != AgentToolAction.Command || environment == null) return nativeSafetySnapshot(context, call)
        val evidence = if (directory != null && command != null) {
            commandEvidence.captureCommandEvidence(
                directory,
                command,
                environment.environment,
                commandReviewFiles(call.arguments),
            )
        } else {
            null
        }
        return nativeSafetySnapshot(context, call, evidence)
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
        // What decided the outcome, for the one central log line: user, policy or error.
        var source = "policy"
        val result = guarded(onFailure = { source = "error" }) {
            if (!isEnabled(context, call)) return@guarded denied("Native tool became unavailable")
            source = "user"
            if (!context.permissions.request(approval)) return@guarded denied("The user declined this action")
            currentCoroutineContext().ensureActive()
            if (isEnabled(context, call)) {
                NativePreflight.Ready(NativeVerdict.Allow)
            } else {
                source = "policy"
                denied("Native tool became unavailable")
            }
        }
        log.i {
            "Native tool confirmation name=${call.name} action=${call.action} outcome=${result.outcome()} " +
                "source=$source"
        }
        return (result as NativePreflight.Ready).verdict
    }

    private suspend fun guarded(onFailure: () -> Unit, block: suspend () -> NativePreflight): NativePreflight = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFailure()
        log.w(IllegalStateException("Native authorization failed (${e::class.simpleName.orEmpty()})")) {
            "Native tool authorization failed"
        }
        denied("Native tool authorization failed")
    }

    private suspend fun isEnabled(context: AgentToolContext, call: NativeToolCall): Boolean =
        policy(context).allows(call)

    private suspend fun policy(context: AgentToolContext): ResolvedToolPolicy? = policies.resolve(
        ToolPolicyScope(context.session.engine, context.workspace, context.session, context.target),
        hostedNames(),
        isExecuting = true,
    )
}

private fun ResolvedToolPolicy?.allows(call: NativeToolCall): Boolean =
    this != null && call.name !in nativeOff && call.name in nativeOn

private const val HOOK_CHARS = 2_000
private const val APPROVAL_CHARS = 8_000

/** Private preflight state; only the dispatcher may bind an approval to a registered invocation. */
internal sealed interface NativePreflight {
    data class Ready(val verdict: NativeVerdict) : NativePreflight
    data class Ask(val approval: AgentToolApproval) : NativePreflight
}

private fun denied(reason: String): NativePreflight = NativePreflight.Ready(NativeVerdict.Deny(reason))

/** Log form without reasons: they may quote hook text, paths or commands. */
private fun NativePreflight.outcome(): String = when (this) {
    is NativePreflight.Ask -> "ask"
    is NativePreflight.Ready -> if (verdict == NativeVerdict.Allow) "allow" else "deny"
}
