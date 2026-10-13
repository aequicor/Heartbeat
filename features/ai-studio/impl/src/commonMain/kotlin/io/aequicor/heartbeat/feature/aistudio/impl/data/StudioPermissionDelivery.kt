package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

/**
 * Sending Decide only commits the command; the native effect may fail later. Observe the captured handle before
 * sending and await acknowledgement of this exact turn/request. A lost/terminal handle cannot confirm delivery.
 * Cancelling the profile cancels the waiter, without pretending that the engine withdrew its pending request.
 */
internal suspend fun deliverStudioPermission(
    state: StateFlow<ActiveSessionState>,
    decision: PermissionDecision,
    send: suspend () -> Unit,
) = coroutineScope {
    val settled = async(start = CoroutineStart.UNDISPATCHED) {
        state.mapNotNull { it.permissionResult(decision) }.first()
    }
    try {
        send()
        check(settled.await()) { "Permission delivery was not acknowledged" }
        Log.tag("StudioPermissionDelivery").i { "Native permission delivery acknowledged" }
    } finally {
        settled.cancel()
    }
}

private fun ActiveSessionState.permissionResult(decision: PermissionDecision): Boolean? {
    val turn = when (this) {
        is ActiveSessionState.Ready -> lastTurn
        is ActiveSessionState.Submitting -> turn
        is ActiveSessionState.Running -> turn
        is ActiveSessionState.AwaitingUserAction -> turn
        is ActiveSessionState.Interrupting -> turn
        is ActiveSessionState.Unavailable -> activeTurn ?: lastTurn
        is ActiveSessionState.Closing, ActiveSessionState.Closed -> null
    }
    if (turn?.id == decision.turn && decision.request in turn.resolvedPermissions) return true
    if (turn?.id != decision.turn || turn.outcome != null || this is ActiveSessionState.Unavailable) return false
    return null
}
