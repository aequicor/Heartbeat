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
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/** Pending extension dialogs and hosted approvals, confined to the session's main dispatcher. */
internal class PiSessionPermissions(
    private val connection: () -> PiConnection?,
    private val send: suspend (ActiveSessionIntent) -> SendResult,
    private val isClosed: () -> Boolean,
    private val isCurrent: (TurnId) -> Boolean,
    private val failed: suspend (EngineFailure) -> Unit,
) {
    private val log = Log.tag("PiSessionPermissions")
    val permissions = mutableMapOf<PermissionRequestId, PermissionRequest>()
    val decisions = mutableSetOf<PermissionRequestId>()
    private val dialogs = mutableMapOf<PermissionRequestId, PiDialog>()

    /** Surfaces [dialog] to the user; a malformed request or one nobody can answer is dismissed. */
    suspend fun await(id: String, dialog: PiDialog?) {
        val request = dialog?.request
        if (request == null || isClosed()) {
            dismiss(id)
            return
        }
        permissions[request.id] = request
        dialogs[request.id] = dialog
        if (send(ActiveSessionIntent.Internal.PermissionNeeded(request)) == SendResult.Accepted) {
            log.i { "Pi dialog awaits user answer" }
        } else {
            permissions.remove(request.id)
            dialogs.remove(request.id)
            dismiss(id)
        }
    }

    suspend fun answer(decision: PermissionDecision, hostedTools: PiHostedSessionTools) {
        val request = permissions.remove(decision.request) ?: return
        if (hostedTools.answer(decision)) return
        val dialog = dialogs.remove(decision.request)
        val origin = connection()
        var isRetryable = false
        try {
            val isAllowed = decision.option == PiApprovalAllow
            val reply = dialog?.reply(decision) ?: ("confirmed" to JsonPrimitive(isAllowed))
            (origin ?: piFailure(EngineFailure.Engine(EngineFailureReason.Unavailable)))
                .respondToUi(decision.request.value, reply)
            // Pi does not acknowledge dialog answers; handing the answer to the process resolves the request.
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
            if (isCurrent(decision.turn) && connection() === origin) {
                isRetryable = true
                permissions[decision.request] = request
                dialog?.let { dialogs[decision.request] = it }
                failed(e.failure)
            }
        } finally {
            if (!isRetryable) {
                withContext(NonCancellable) {
                    send(ActiveSessionIntent.Internal.PermissionResolved(decision.turn, decision.request))
                }
            }
        }
    }

    suspend fun dismiss(id: String) = connection()?.dismissUi(id) ?: Unit

    fun clear(isClearingDecisions: Boolean = true) {
        permissions.clear()
        dialogs.clear()
        if (isClearingDecisions) decisions.clear()
    }
}
