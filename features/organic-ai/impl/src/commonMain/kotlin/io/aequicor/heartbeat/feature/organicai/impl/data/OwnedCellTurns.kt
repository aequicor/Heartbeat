package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnStop
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** Cancels the inspected turn ID only; a newer human request never inherits the organic stop. */
internal suspend fun ActiveSession.stopOwned(request: RequestId): OwnedTurnStop {
    val current = state.value
    val active = current.activeTurn()?.takeIf { it.request == request }
        ?: return current.completed(request)?.let { OwnedTurnStop.Confirmed(it) } ?: OwnedTurnStop.Unconfirmed
    cancelQuietly(active.id)
    val result = withTimeoutOrNull(10.seconds) {
        state.first { it.completed(request) != null || it.activeTurn()?.id != active.id }
    }?.completed(request)
    return result?.let { OwnedTurnStop.Confirmed(it) } ?: OwnedTurnStop.Unconfirmed
}

private fun ActiveSessionState.completed(request: RequestId): Turn? =
    (this as? ActiveSessionState.Ready)?.lastTurn?.takeIf {
        it.request == request && it.outcome != null && it.outcome != TurnOutcome.Unknown
    }
