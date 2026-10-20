package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** An immutable approval bound to the originating native turn and connection. Never runs on the RPC reader. */
internal class PiNativeApproval(
    private val environment: PiSessionEnvironment,
    private val hosted: PiHostedSessionTools,
    private val connection: PiConnection,
    private val isCurrent: () -> Boolean,
    private val failed: suspend (EngineFailure) -> Unit,
) {
    private val log = Log.tag("PiNativeApproval")

    suspend fun answer(id: String, call: PiApprovalCall, turn: Turn, context: AgentToolContext) {
        try {
            val result = call.result
            val decision = if (result == null) authorize(id, call, turn, context) else null
            val response = if (result != null) {
                val note = environment.tools.afterHosted(context, call.tool, call.arguments, result)
                "value" to JsonPrimitive(note.orEmpty())
            } else {
                checkNotNull(decision).verdict.response(call.isStructuredReply)
            }
            currentCoroutineContext().ensureActive()
            // Recheck after the gate, before sending Allow to the captured process.
            if (!isCurrent()) {
                if (decision != null) log.d { "Pi native ${call.tool} decision dropped: the turn is no longer current" }
                return
            }
            decision?.let { audit(call.tool, it) }
            reply(id, response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e.withoutDetails()) { "Pi native approval failed; the call stays blocked" }
            val response = if (call.result == null) {
                NativeVerdict.Deny("Heartbeat could not complete tool authorization").response(call.isStructuredReply)
            } else {
                "value" to JsonPrimitive("")
            }
            reply(id, response)
        }
    }

    private suspend fun reply(id: String, response: Pair<String, JsonElement>) {
        if (!connection.isOpen) return
        try {
            connection.respondToUi(id, response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e.withoutDetails()) { "Pi native approval response was not delivered" }
            // A lost confirm cannot be retried from a permission already resolved by the gate.
            // Terminate this process so no invisible extension dialog can keep it running forever.
            val isActive = isCurrent()
            connection.close()
            if (isActive) {
                withContext(NonCancellable) {
                    failed((e as? EngineException)?.failure ?: EngineFailure.Unknown())
                }
            }
        }
    }

    private suspend fun authorize(id: String, call: PiApprovalCall, turn: Turn, context: AgentToolContext): Decision {
        val classified = classify(call)
            ?: return Decision(NativeVerdict.Deny("Unknown native tool"), false, false)
        val (native, isHostTermination) = classified
        var isAsked = false
        val bound = context.copy(
            callId = call.toolCallId,
            permissions = AgentToolPermissions { approval ->
                if (!isCurrent()) return@AgentToolPermissions false
                val request = approvalRequest(id, turn.id, call, isHostTermination)
                    .copy(description = approval.description)
                isAsked = true
                hosted.approval(turn, request, PiApprovalAllow)
            },
        )
        val verdict = if (call.tool in SearchTools) {
            environment.tools.authorizeHosted(bound, call.tool, call.arguments)
        } else {
            environment.tools.authorizeNative(bound, native)
        }
        return Decision(verdict, isAsked, native.action == AgentToolAction.Read)
    }

    /**
     * Audit without the target. User decisions and anything beyond reading reach INFO; automatic reads follow
     * trust or policy on every native lookup (search tools are also audited by the hosted gate), so VERBOSE.
     */
    private fun audit(tool: String, decision: Decision) {
        val source = if (decision.isAsked) "user" else "trust or policy"
        val verdict = if (decision.verdict.isAllowed) "allowed" else "denied"
        if (decision.isAsked || !decision.isRead) {
            log.i { "Pi native $tool $verdict by $source" }
        } else {
            log.v { "Pi native $tool $verdict by $source" }
        }
    }

    private data class Decision(val verdict: NativeVerdict, val isAsked: Boolean, val isRead: Boolean)
    private fun classify(call: PiApprovalCall): Pair<NativeToolCall, Boolean>? {
        val group = piToolGroup(call.tool).takeIf { call.tool !in SearchTools }
        if (group != null) return extensionCall(call, group) to false
        val action = if (call.tool in SearchTools) {
            AgentToolAction.Read
        } else {
            piNativeAction(call.tool)
                ?: return null
        }
        val isHostTermination = action == AgentToolAction.Command && environment.nativeCalls.terminatesHost(call.target)
        val workspace = connection.workingDirectory?.toString()
        val native = NativeToolCall(
            call.tool,
            action,
            paths = if (action == AgentToolAction.Command) emptyList() else listOf(call.path ?: call.target),
            command = call.target.takeIf { action == AgentToolAction.Command },
            arguments = call.arguments,
            workingDirectory = workspace,

        )
        return native to isHostTermination
    }

    /** Extension tools describe their full action to the shared permission gate. */
    private fun extensionCall(call: PiApprovalCall, group: PiToolGroups): NativeToolCall = NativeToolCall(
        group.policyName,
        group.action,
        command = listOf(call.tool, call.target).filter { it.isNotBlank() }.joinToString(" "),
        arguments = call.arguments,
        workingDirectory = connection.workingDirectory?.toString(),

    )
}

/** Input RPC carries the denial unchanged; legacy confirm requests still receive their Boolean contract. */
private fun NativeVerdict.response(isStructured: Boolean): Pair<String, JsonElement> = if (isStructured) {
    val decision = buildJsonObject {
        put("allowed", isAllowed)
        if (this@response is NativeVerdict.Deny) put("reason", reason)
    }
    "value" to JsonPrimitive(decision.toString())
} else {
    "confirmed" to JsonPrimitive(isAllowed)
}

private val NativeVerdict.isAllowed: Boolean get() = this == NativeVerdict.Allow
