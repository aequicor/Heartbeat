package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

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
            val verdict = authorize(id, call, turn, context)
            currentCoroutineContext().ensureActive()
            // Recheck after the gate, before sending Allow to the captured process.
            if (!isCurrent()) return
            reply(id, verdict == NativeVerdict.Allow)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e.withoutDetails()) { "Pi native approval failed; the call stays blocked" }
            reply(id, false)
        }
    }

    private suspend fun reply(id: String, isAllowed: Boolean) {
        if (!connection.isOpen) return
        try {
            connection.respondToUi(id, "confirmed" to JsonPrimitive(isAllowed))
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

    private suspend fun authorize(
        id: String,
        call: PiApprovalCall,
        turn: Turn,
        context: AgentToolContext,
    ): NativeVerdict {
        val action = piNativeAction(call.tool) ?: return NativeVerdict.Deny("Unknown native tool")
        val isHostTermination = action == AgentToolAction.Command && environment.nativeCalls.terminatesHost(call.target)
        val path = call.path
        val workspace = connection.workingDirectory?.toString()
        val isEdit = action == AgentToolAction.Edit && path != null && workspace != null &&
            environment.nativeCalls.isWorkspaceEdit(path, workspace)
        val native = NativeToolCall(
            call.tool,
            action,
            paths = if (action == AgentToolAction.Command) emptyList() else listOf(call.path ?: call.target),
            command = call.target.takeIf { action == AgentToolAction.Command },
            covered = { trust ->
                !isHostTermination && (
                    action == AgentToolAction.Read || trust == TrustLevel.Full ||
                        (trust == TrustLevel.AutoEdits && isEdit)
                )
            },
        )
        val bound = context.copy(
            permissions = AgentToolPermissions { approval ->
                if (!isCurrent()) return@AgentToolPermissions false
                val request = approvalRequest(id, turn.id, call, isHostTermination)
                    ?.copy(description = approval.description) ?: return@AgentToolPermissions false
                hosted.approval(turn, request, PiApprovalAllow)
            },
        )
        return environment.tools.authorizeNative(bound, native)
    }
}
