package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
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
