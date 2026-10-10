package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionEffect
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionOutput
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RestoresSessionTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

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

/** Persisted adapter identity and server inspection independent of facade turn-id mappings. */
internal class CodexTurnRecovery(
    private val ref: SessionRef,
    private val machine: Machine<ActiveSessionState, ActiveSessionIntent, ActiveSessionOutput>,
    private val nativeTurns: MutableMap<String, TurnId>,
    private val readNativeHistory: suspend () -> List<JsonObject>?,
    private val outcome: (JsonObject) -> TurnOutcome,
    private val setNative: (String) -> Unit,
) : RestoresSessionTurns {
    override suspend fun checkpoint(request: RequestId): String? {
        val turn = machine.state.value.currentTurn() ?: (machine.state.value as? ActiveSessionState.Ready)?.lastTurn
        if (turn?.request != request) return null
        val native = nativeTurns.entries.firstOrNull { it.value == turn.id }?.key ?: return null
        return json(
            "session" to Json.encodeToJsonElement(SessionRef.serializer(), ref),
            "turn" to Json.encodeToJsonElement(Turn.serializer(), turn),
            "native" to native.json(),
        ).toString()
    }

    /** A fresh server snapshot, including the persisted native id, never a cached Ready assertion. */
    override suspend fun inspect(checkpoint: String?): TurnInspection {
        val receipt = checkpoint?.let {
            val fields = Json.parseToJsonElement(it).jsonObject
            CodexTurnReceipt(
                Json.decodeFromJsonElement(SessionRef.serializer(), fields.getValue("session")),
                Json.decodeFromJsonElement(Turn.serializer(), fields.getValue("turn")),
                checkNotNull(fields.text("native")),
            )
        }
        if (receipt != null && receipt.session != ref) return TurnInspection.Unknown
        val turns = readNativeHistory() ?: return TurnInspection.Unknown
        val matched = turns.firstOrNull { it.text("id") == receipt?.native }
        val running = turns.filter { it.text("status") == IN_PROGRESS }
        if (running.any { it.text("id") != receipt?.native }) return TurnInspection.Unknown
        return if (matched == null || receipt?.turn?.request == null) {
            TurnInspection.Idle
        } else if (matched.text("status") != IN_PROGRESS) {
            TurnInspection.Observed(checkNotNull(receipt.turn.request), outcome(matched))
        } else {
            adopt(receipt)
        }
    }

    private suspend fun adopt(receipt: CodexTurnReceipt): TurnInspection {
        val remembered = receipt.turn.copy(outcome = null)
        val current = machine.state.value.currentTurn()
        if (current != null && current.request != remembered.request) return TurnInspection.Unknown
        nativeTurns[receipt.native] = remembered.id
        setNative(receipt.native)
        if (machine.state.value is ActiveSessionState.Ready) {
            machine.send(ActiveSessionIntent.Internal.Failed(null, EngineFailure.Session(SessionFailureReason.Busy)))
        }
        if (machine.state.value is ActiveSessionState.Unavailable) {
            machine.send(ActiveSessionIntent.Internal.Synchronized(remembered))
        }
        return recheckAdoption(receipt)
    }

    /** Close the snapshot-to-adoption window: completions before the mapping existed had no observer. */
    private suspend fun recheckAdoption(receipt: CodexTurnReceipt): TurnInspection {
        val turns = readNativeHistory() ?: return TurnInspection.Unknown
        val latest = turns.firstOrNull { it.text("id") == receipt.native } ?: return TurnInspection.Unknown
        val terminal = (machine.state.value as? ActiveSessionState.Ready)?.lastTurn
            ?.takeIf { it.id == receipt.turn.id }?.outcome
        val result = terminal ?: if (latest.text("status") == IN_PROGRESS) null else outcome(latest)
        if (result != null) machine.send(ActiveSessionIntent.Internal.Finished(receipt.turn.id, result))
        return TurnInspection.Observed(checkNotNull(receipt.turn.request), result)
    }

    private fun ActiveSessionState.currentTurn(): Turn? = when (this) {
        is ActiveSessionState.Running -> turn
        is ActiveSessionState.AwaitingUserAction -> turn
        is ActiveSessionState.Interrupting -> turn
        is ActiveSessionState.Submitting -> turn
        is ActiveSessionState.Unavailable -> activeTurn
        is ActiveSessionState.Ready, is ActiveSessionState.Closing, ActiveSessionState.Closed -> null
    }

    private companion object {
        const val IN_PROGRESS = "inProgress"
    }
}

/** Native ids remain opaque to the studio and survive loss of in-memory facade correlations. */
private data class CodexTurnReceipt(val session: SessionRef, val turn: Turn, val native: String)
