package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Collects complete native assistant messages for atomic correlation with the terminal turn receipt.
 * Only hashes are stored: neither content nor message order can be used to guess an unobserved answer.
 * A crash before observing message_end leaves the original turn unresolved, so recovery may need another turn.
 */
internal class PiAnswerCorrelations(
    private val records: PiTurnRecords,
    private val ref: SessionRef,
    private val route: ExecutionRoute,
    private val ownership: String,
) {
    private val log = Log.tag("PiAnswerCorrelations")
    private var current: TurnId? = null
    private val observed = mutableMapOf<String, TurnId>()

    suspend fun restore(): Map<String, TurnId?> = guarded {
        records.get(ref)?.also(::validate)?.answerTurns.orEmpty()
    }

    /** Session callbacks are main-confined and supply only the current owned turn. No IO runs on message delivery. */
    fun record(event: JsonObject, turn: TurnId?) {
        if (turn == null || event.string("type") != "message_end") return
        val message = event["message"] as? JsonObject ?: return
        val key = piAnswerKey(message) ?: return
        if (current != turn) {
            current = turn
            observed.clear()
        }
        observed[key] = turn
    }

    /** Merged by PiTurnJournal.finish in the same atomic update as the terminal receipt. */
    fun pending(turn: TurnId): Map<String, TurnId?> = if (current == turn) observed.toMap() else emptyMap()

    private fun validate(snapshot: PiTurnSnapshot) {
        check(snapshot.ref == ref && snapshot.route == route && snapshot.ownership == ownership) {
            "Pi answer ownership changed"
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: EngineException) {
        throw error
    } catch (error: Exception) {
        log.w(IllegalStateException("Answer correlation failed (${error::class.simpleName.orEmpty()})")) {
            "Pi answer correlation unavailable"
        }
        piFailure(EngineFailure.Session(SessionFailureReason.NotResumable))
    }
}

/** Full native message identity, insensitive only to JSON object key ordering; timestamps alone are not unique. */
internal fun piAnswerKey(message: JsonObject): String? =
    if (message.string("role") == "assistant") fingerprint(message.canonicalAnswer().toString()) else null

private fun JsonElement.canonicalAnswer(): JsonElement = when (this) {
    is JsonObject -> JsonObject(toSortedMap().mapValues { it.value.canonicalAnswer() })
    is JsonArray -> JsonArray(map { it.canonicalAnswer() })
    is JsonPrimitive -> this
}
