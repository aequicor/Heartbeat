package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Sends the native extension dialog answer; hosted tools resolve through their bridge instead. */
internal suspend fun PiConnection.respondToUi(id: String, reply: Pair<String, JsonElement>) {
    send(
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("extension_ui_response"),
                "id" to JsonPrimitive(id),
                reply,
            ),
        ),
    )
}

/** Declines a dialog nobody can answer; failure is not proof that the native process stopped. */
internal suspend fun PiConnection.dismissUi(id: String) {
    try {
        if (isOpen) respondToUi(id, "cancelled" to JsonPrimitive(true))
    } catch (e: EngineException) {
        Log.tag("PiSession").w(e) { "Pi dialog dismissal was not delivered" }
    }
}
