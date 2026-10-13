package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.LimitScope
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val log = Log.tag("PiProviderLimit")

/**
 * Recognizes established limit codes only in complete JSON bodies following Pi/SDK's leading HTTP status.
 * Pi may flatten the provider's error object; both that shape and an `error` envelope are supported.
 * Arbitrary messages, malformed/truncated bodies and codes under unrelated fields never establish a limit.
 * The body and parser diagnostics may contain secrets and must never escape this boundary.
 */
@HighFrequency
internal fun piProviderLimit(status: Int?, message: String?): EngineFailure? {
    if (status !in LimitStatuses || message == null) return null
    val body = message.drop(HTTP_STATUS_LENGTH).trimStart().removePrefix(":").trimStart()
    if (!body.startsWith("{") || body.length > MAX_ERROR_BODY_LENGTH) return null
    val root = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (error: SerializationException) {
        log.w(error.redactedFailure()) { "Pi provider error body could not be classified" }
        null
    } ?: return null
    val error = root["error"] as? JsonObject ?: root
    return when (error.string("code")) {
        "insufficient_quota" -> if (status == HttpStatusCode.TooManyRequests.value) {
            EngineFailure.QuotaExceeded(LimitScope.Unknown)
        } else {
            null
        }

        "context_length_exceeded" -> if (status in ContextStatuses) EngineFailure.ContextLimitExceeded() else null

        else -> null
    }
}

private val ContextStatuses = setOf(
    HttpStatusCode.BadRequest.value,
    HttpStatusCode.PayloadTooLarge.value,
    HttpStatusCode.UnprocessableEntity.value,
)
private val LimitStatuses = ContextStatuses + HttpStatusCode.TooManyRequests.value
private const val HTTP_STATUS_LENGTH = 3
private const val MAX_ERROR_BODY_LENGTH = 16_384
