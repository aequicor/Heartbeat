package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionEffect
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.serialization.json.JsonObject

/** A stored active turn has no process ownership proof; only native terminal evidence can settle it. */
internal fun CodexTurnSnapshot?.codexInitialState(): ActiveSessionState = this?.active?.let {
    ActiveSessionState.Unavailable(EngineFailure.Session(SessionFailureReason.NotResumable), it.turn, last?.turn)
} ?: ActiveSessionState.Ready(this?.last?.turn)

internal fun CodexTurnSnapshot?.codexNativeTurns(): Map<String, TurnId> =
    listOfNotNull(this?.active, this?.last).mapNotNull { record ->
        record.nativeId?.let { it to record.turn.id }
    }.toMap()

internal fun codexTurnOutcome(native: JsonObject, binding: EngineBindingId): TurnOutcome =
    when (native.text("status")) {
        "completed" -> TurnOutcome.Completed

        "interrupted" -> TurnOutcome.Cancelled

        "failed" -> {
            val failure = codexTurnFailure(native["error"] as? JsonObject, binding)
            Log.tag("CodexSession").w { "Codex turn failed code=${failure.code}" }
            TurnOutcome.Failed(failure)
        }

        else -> TurnOutcome.Unknown
    }

internal fun ActiveSessionEffect.codexTurnId(): TurnId? = when (this) {
    is ActiveSessionEffect.Submit -> turn.id
    is ActiveSessionEffect.Cancel -> turn
    is ActiveSessionEffect.Decide -> decision.turn
    is ActiveSessionEffect.Recheck -> turn
    ActiveSessionEffect.Release -> null
}

internal fun ActiveSessionState.codexCurrentTurn(): Turn? = when (this) {
    is ActiveSessionState.Submitting -> turn
    is ActiveSessionState.Running -> turn
    is ActiveSessionState.AwaitingUserAction -> turn
    is ActiveSessionState.Interrupting -> turn
    is ActiveSessionState.Unavailable -> activeTurn
    is ActiveSessionState.Ready, is ActiveSessionState.Closing, ActiveSessionState.Closed -> null
}
