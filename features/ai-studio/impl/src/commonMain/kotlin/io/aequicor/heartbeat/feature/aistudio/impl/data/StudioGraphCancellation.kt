package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** A recovered or already interrupting turn must reach terminal without issuing a second cancellation. */
internal suspend fun stopGraphTurn(
    active: ActiveSession,
    known: Set<String>,
    cancel: suspend (TurnId) -> Unit,
): Boolean {
    val state = active.state.value
    val turn = state.activeTurn() ?: return state.lastCompletedTurn()?.request?.value in known
    if (turn.request?.value !in known) return false
    if (state !is ActiveSessionState.Interrupting) cancel(turn.id)
    return withTimeoutOrNull(30.seconds) {
        active.state.first { it.isTerminalFor(turn.id) }
        true
    } == true
}
