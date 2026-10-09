package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId

/**
 * Current host observation of one accepted request. It is not a terminal receipt or permission to resubmit.
 * Unlike PermissionRequested notifications, [permissions] reflects resolved decisions as well. Reading this
 * snapshot must not open a session, generate text, or change native execution.
 */
public data class HelperProgress(
    val request: RequestId,
    val session: SessionRef,
    val turn: TurnId,
    val permissions: List<PermissionRequest> = emptyList(),
) {
    init {
        require(permissions.all { it.turn == turn } && permissions.map { it.id }.distinct().size == permissions.size)
    }

    override fun toString(): String = "HelperProgress(request=$request, ***)"
}
