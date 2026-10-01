package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

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
