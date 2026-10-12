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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/** Pending extension dialogs and hosted approvals, confined to the session's main dispatcher. */
internal class PiSessionPermissions(
    private val connection: () -> PiConnection?,
    private val send: suspend (ActiveSessionIntent) -> SendResult,
    private val isClosed: () -> Boolean,
    private val isCurrent: (TurnId) -> Boolean,
    private val failed: suspend (EngineFailure) -> Unit,
    private val scope: CoroutineScope,
) {
    private val log = Log.tag("PiSessionPermissions")
    val permissions = mutableMapOf<PermissionRequestId, PermissionRequest>()
    val decisions = mutableSetOf<PermissionRequestId>()
    private val deadlines = mutableMapOf<PermissionRequestId, Job>()
    private val dialogs = mutableMapOf<PermissionRequestId, PiDialog>()

    /** Surfaces [dialog] to the user; a malformed request or one nobody can answer is dismissed. */
    suspend fun await(id: String, dialog: PiDialog?, timeoutMillis: Long? = null) {
        val request = dialog?.request
        if (request == null || isClosed()) {
            dismiss(id)
            return
        }
        deadlines.remove(request.id)?.cancel()
        permissions[request.id] = request
        dialogs[request.id] = dialog
        if (send(ActiveSessionIntent.Internal.PermissionNeeded(request)) == SendResult.Accepted) {
            log.i { "Pi dialog awaits user answer" }
            if (timeoutMillis != null && timeoutMillis > 0) {
                deadlines[request.id] = scope.launch {
                    delay(timeoutMillis)
                    if (dialogs[request.id] === dialog) {
                        dialogs.remove(request.id)
                        permissions.remove(request.id)
                        decisions.remove(request.id)
                        deadlines.remove(request.id)
                        log.i { "Pi dialog expired" }
                        send(ActiveSessionIntent.Internal.PermissionResolved(request.turn, request.id))
                    }
                }
            }
        } else {
            permissions.remove(request.id)
            dialogs.remove(request.id)
            dismiss(id)
        }
    }

    suspend fun answer(decision: PermissionDecision, hostedTools: PiHostedSessionTools) {
        val request = permissions.remove(decision.request) ?: return
        if (hostedTools.answer(decision)) return
        val dialog = dialogs[decision.request]
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
            if (isRetryable(decision, origin, dialog)) {
                isRetryable = true
                permissions[decision.request] = request
                dialog?.let { dialogs[decision.request] = it }
                failed(e.failure)
            }
        } finally {
            if (!isRetryable) {
                dialogs.remove(decision.request)
                deadlines.remove(decision.request)?.cancel()
                withContext(NonCancellable) {
                    send(ActiveSessionIntent.Internal.PermissionResolved(decision.turn, decision.request))
                }
            }
        }
    }

    private fun isRetryable(decision: PermissionDecision, origin: PiConnection?, dialog: PiDialog?): Boolean =
        isCurrent(decision.turn) && connection() === origin && dialogs[decision.request] === dialog

    suspend fun dismiss(id: String) = connection()?.dismissUi(id) ?: Unit

    fun clear(isClearingDecisions: Boolean = true) {
        deadlines.values.forEach(Job::cancel)
        deadlines.clear()
        permissions.clear()
        dialogs.clear()
        if (isClearingDecisions) decisions.clear()
    }
}
