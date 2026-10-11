package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Live session authority consulted on main when receiving or answering a native Pi dialog. */
internal interface PiSessionApprovalHost {
    val turn: Turn?
    val trust: TrustLevel
    val isClosed: Boolean
    val isAutoAnswerAllowed: Boolean
    val connection: PiConnection?
    val hostedTools: PiHostedSessionTools
    suspend fun send(intent: ActiveSessionIntent): SendResult
    suspend fun failed(failure: EngineFailure)
}

/** Pending native dialogs and approval replies of one session; all mutable state is confined to main. */
internal class PiSessionApprovals(
    private val environment: PiSessionEnvironment,
    private val host: PiSessionApprovalHost,
) {
    private val log = Log.tag("PiSession")

    /** Shared with hosted tools so native reconciliation sees every outstanding permission. */
    val permissions = mutableMapOf<PermissionRequestId, PermissionRequest>()
    private val decisions = mutableSetOf<PermissionRequestId>()
    private val dialogs = mutableMapOf<PermissionRequestId, PiDialog>()

    /** Forgets requests whose process or turn no longer exists. */
    fun clear() {
        permissions.clear()
        dialogs.clear()
        decisions.clear()
    }

    suspend fun request(record: JsonObject) {
        val id = record.string("id") ?: return
        // Fire-and-forget UI (notify, status, widgets) needs no answer.
        if (record.string("method") !in DIALOG_METHODS) return
        if (record.string("method") == "confirm" && record.string("title") == APPROVAL_TITLE) {
            approval(id, record.string("message"))
        } else {
            val dialog = host.turn?.let { PiDialog.from(record, id, it.id) }
            await(id, dialog?.request, dialog)
        }
    }

    private suspend fun approval(id: String, message: String?) {
        val call = approvalCall(message)
        val level = host.trust
        if (call != null && isTrusted(call, level) && host.isAutoAnswerAllowed) {
            allow(id, call.tool, level)
        } else {
            val active = host.turn
            await(id, if (call != null && active != null) approvalRequest(id, active.id, call) else null, null)
        }
    }

    /** A malformed request or a dialog nobody can answer is dismissed instead of stalling the native turn. */
    private suspend fun await(id: String, request: PermissionRequest?, dialog: PiDialog?) {
        if (request == null || host.isClosed) {
            dismiss(id)
            return
        }
        permissions[request.id] = request
        dialog?.let { dialogs[request.id] = it }
        if (host.send(ActiveSessionIntent.Internal.PermissionNeeded(request)) == SendResult.Accepted) {
            log.i { if (dialog == null) "Pi tool call awaits user approval" else "Pi dialog awaits user answer" }
        } else {
            permissions.remove(request.id)
            dialogs.remove(request.id)
            dismiss(id)
        }
    }

    private suspend fun isTrusted(call: PiApprovalCall, level: TrustLevel): Boolean {
        if (!host.isAutoAnswerAllowed) return false
        val workspace = host.connection?.workingDirectory
        return withContext(environment.dispatchers.io) { level.answers(call, workspace) }
    }

    private suspend fun allow(id: String, tool: String, level: TrustLevel) {
        try {
            rpc().respondToUi(id, "confirmed" to JsonPrimitive(true))
            log.i { "Pi tool call allowed by trust level $level: $tool" }
        } catch (e: EngineException) {
            log.w(e) { "Pi trusted approval was not delivered" }
            if (host.turn != null) host.failed(e.failure)
        }
    }

    /** Replies remain profile-owned, independent of the state machine effect or the caller's lifetime. */
    fun decide(decision: PermissionDecision) {
        if (decisions.add(decision.request)) {
            environment.profile.coroutineScope.launch(environment.dispatchers.main) { answer(decision) }
        }
    }

    private suspend fun answer(decision: PermissionDecision) {
        val request = permissions.remove(decision.request) ?: return
        if (host.hostedTools.answer(decision)) return
        val dialog = dialogs.remove(decision.request)
        try {
            val isAllowed = decision.option == PiApprovalAllow
            val reply = dialog?.reply(decision) ?: ("confirmed" to JsonPrimitive(isAllowed))
            rpc().respondToUi(decision.request.value, reply)
            // Pi does not acknowledge dialog answers; handing the answer to the process resolves the request.
            host.send(ActiveSessionIntent.Internal.PermissionResolved(decision.turn, decision.request))
            log.i {
                when {
                    dialog != null -> "Pi dialog answered by user: ${reply.first}"
                    isAllowed -> "Pi tool call allowed by user"
                    else -> "Pi tool call denied by user"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "Pi approval answer was not delivered" }
            decisions.remove(decision.request)
            // The request is still pending in Pi: keep it answerable so a retry can deliver the answer.
            permissions[decision.request] = request
            dialog?.let { dialogs[decision.request] = it }
            if (host.turn?.id == decision.turn) host.failed(e.failure)
        }
    }

    suspend fun dismiss() {
        val pending = permissions.keys.filter { !host.hostedTools.contains(it) }
        host.hostedTools.dismiss()
        permissions.clear()
        dialogs.clear()
        pending.forEach { dismiss(it.value) }
    }

    private suspend fun dismiss(id: String) {
        try {
            host.connection?.takeIf { it.isOpen }?.respondToUi(id, "cancelled" to JsonPrimitive(true))
        } catch (e: EngineException) {
            log.w(e) { "Pi dialog dismissal was not delivered" }
        }
    }

    private fun rpc(): PiConnection = host.connection
        ?: piFailure(EngineFailure.Engine(EngineFailureReason.Unavailable))

    private companion object {
        // Must match APPROVAL_TITLE in resources/pi/heartbeat-approval.ts.
        const val APPROVAL_TITLE = "heartbeat.tool-approval"
        val DIALOG_METHODS = setOf("select", "confirm", "input", "editor")
    }
}
