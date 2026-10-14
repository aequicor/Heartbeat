package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.hostedToolName
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiTools
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val cardCallLog = Log.tag("StudioUiCardCalls")
private val cardCallJson = Json { ignoreUnknownKeys = true }

/** Classifies legacy receipts; arguments never supply a card id or make an unsuccessful result visible. */
@HighFrequency
internal fun isLibraryUiShow(name: String, arguments: String): Boolean {
    val text = arguments.trim()
    if (hostedToolName(name) != ServiceUiTools.SHOW || !text.startsWith("{") || !text.endsWith("}")) return false
    return try {
        !cardCallJson.decodeFromString<UiShowArguments>(text).surface.isNullOrBlank()
    } catch (error: SerializationException) {
        cardCallLog.w(error.redactedFailure()) { "Interface call arguments cannot classify a historical card" }
        false
    }
}

@Serializable
private data class UiShowArguments(val surface: String? = null)
