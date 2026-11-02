package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome

/** Actual engine identity; a recovery execution may observe an earlier request without submitting its own. */
internal data class StudioNativeTurn(val request: RequestId, val turn: TurnId) {
    override fun toString(): String = "StudioNativeTurn(***)"
}

/** A stale or unavailable live handle cannot substantiate adoption, even if its turn id was seen earlier. */
internal fun ActiveSessionState.matches(native: StudioNativeTurn): Boolean {
    if (this is ActiveSessionState.Submitting) return false
    val running = activeTurn()
    if (running != null) {
        return this !is ActiveSessionState.Unavailable && running.request == native.request && running.id == native.turn
    }
    val completed = lastCompletedTurn() ?: return false
    return completed.request == native.request && completed.id == native.turn &&
        completed.outcome != null && completed.outcome != TurnOutcome.Unknown
}
