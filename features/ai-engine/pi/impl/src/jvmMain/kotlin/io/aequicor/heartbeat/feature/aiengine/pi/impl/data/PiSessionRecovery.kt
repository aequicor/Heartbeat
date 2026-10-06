package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Called only after the previous process's exit; a concurrent shutdown discards the fresh connection. */
internal suspend fun PiSessionConfiguration.reconnect(
    file: String,
    nativeId: String?,
    open: suspend () -> PiConnection,
    ensureOpen: () -> Unit,
    discarded: () -> Unit,
): Pair<PiConnection, JsonObject> {
    ensureOpen()
    val fresh = open()
    var isConfigured = false
    try {
        ensureOpen()
        fresh.reattach(file)
        // switch_session can create a new session if the original file was never persisted.
        if (fresh.command("get_state").string("sessionId") != nativeId) {
            piFailure(EngineFailure.Session(SessionFailureReason.Changed))
        }
        val snapshot = restore(fresh)
        ensureOpen()
        isConfigured = true
        return fresh to snapshot
    } catch (error: EngineException) {
        Log.tag("PiSession").w(error) { "Pi session recovery could not reattach the transcript" }
        throw error
    } finally {
        if (!isConfigured) {
            discarded()
            fresh.close()
        }
    }
}

/** Switches this connection to the native transcript [file]; Pi reports a refused switch as `cancelled`. */
internal suspend fun PiConnection.reattach(file: String): PiConnection = apply {
    val switched = command("switch_session", JsonObject(mapOf("sessionPath" to JsonPrimitive(file))))
    if ((switched["cancelled"] as? JsonPrimitive)?.booleanOrNull == true) {
        piFailure(EngineFailure.Session(SessionFailureReason.Changed))
    }
}

/** Native startup result before live history or state is published. */
internal data class PiStartedSession(val nativeId: String, val snapshot: JsonObject, val branch: PiStoredBranch?)

internal suspend fun PiConnection.startSession(
    configuration: PiSessionConfiguration,
    transcript: PiTranscript?,
): PiStartedSession {
    val stored = transcript?.let { reattach(it.file).storedConversation() }
    command("set_model", configuration.modelFields(configuration.target.model))
    val snapshot = command("get_state")
    val id = snapshot.string("sessionId")
        ?: piFailure(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
    if (transcript != null && transcript.ref.nativeId != id) {
        piFailure(EngineFailure.Session(SessionFailureReason.Changed))
    }
    return PiStartedSession(id, snapshot, stored)
}

/** Only an owned live process or a confirmed replacement may use this idle projection as termination evidence. */
internal fun piReconciliation(
    snapshot: JsonObject,
    turn: Turn?,
    permissions: Collection<PermissionRequest>,
): ActiveSessionIntent.Internal.Synchronized {
    val isBusy = (snapshot["isStreaming"] as? JsonPrimitive)?.booleanOrNull == true ||
        (snapshot["isCompacting"] as? JsonPrimitive)?.booleanOrNull == true
    if (isBusy && turn == null) piFailure(EngineFailure.Session(SessionFailureReason.Changed))
    val completed = if (!isBusy && turn != null) {
        ActiveSessionIntent.Internal.Finished(turn.id, TurnOutcome.Unknown)
    } else {
        null
    }
    return ActiveSessionIntent.Internal.Synchronized(
        if (isBusy) turn else null,
        if (isBusy) permissions.filter { it.turn == turn?.id } else emptyList(),
        completed,
    )
}

/** Assistant messages report a provisional result; only agent_settled makes it terminal. */
internal fun piMessageOutcome(record: JsonObject): TurnOutcome? {
    val message = record["message"] as? JsonObject ?: return null
    if (message.string("role") != "assistant") return null
    return when (message.string("stopReason")) {
        "aborted" -> TurnOutcome.Cancelled
        "error" -> TurnOutcome.Failed(EngineFailure.Unknown())
        else -> TurnOutcome.Completed
    }
}
