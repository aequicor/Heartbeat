package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RestoresSessionTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnInspection
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** A Pi process has no resumable remote turn id: a fresh idle snapshot permits history-based continuation. */
internal class PiTurnRecovery(
    private val ref: () -> SessionRef,
    private val current: () -> Turn?,
    private val snapshot: suspend () -> JsonObject,
) : RestoresSessionTurns {
    override suspend fun checkpoint(request: RequestId): String? =
        current()?.takeIf { it.request == request }?.let { ref().nativeId + ":" + request.value }

    override suspend fun inspect(checkpoint: String?): TurnInspection {
        val snapshot = snapshot()
        val isStreaming = (snapshot["isStreaming"] as? JsonPrimitive)?.booleanOrNull
        val isCompacting = (snapshot["isCompacting"] as? JsonPrimitive)?.booleanOrNull
        if (snapshot.string("sessionId") != ref().nativeId || isStreaming == null || isCompacting == null) {
            return TurnInspection.Unknown
        }
        val current = current()
        val isOwned = current?.request?.let { checkpoint == ref().nativeId + ":" + it.value } == true
        return when {
            isOwned && current.outcome != null -> TurnInspection.Observed(
                checkNotNull(current.request),
                current.outcome,
            )

            isOwned && (isStreaming || isCompacting) -> TurnInspection.Observed(checkNotNull(current.request), null)

            isStreaming || isCompacting -> TurnInspection.Unknown

            else -> TurnInspection.Idle
        }
    }
}
