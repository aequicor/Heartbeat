package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.LimitScope
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val log = Log.tag("PiProviderLimit")

/**
 * Recognizes established provider codes only in complete JSON bodies following Pi/SDK's leading HTTP status.
 * Pi may flatten the provider's error object; both that shape and an `error` envelope are supported.
 * Arbitrary messages, malformed/truncated bodies and codes under unrelated fields keep the HTTP fallback.
 * The body and parser diagnostics may contain secrets and must never escape this boundary.
 */
@HighFrequency
internal fun piProviderLimit(status: Int?, message: String?, provider: String?): EngineFailure? {
    if (status !in LimitStatuses || message == null) return null
    val code = piProviderCode(message) ?: return null
    return when (code) {
        "insufficient_quota" -> if (status == HttpStatusCode.TooManyRequests.value) {
            EngineFailure.QuotaExceeded(LimitScope.Unknown)
        } else {
            null
        }

        "context_length_exceeded" -> if (status in ContextStatuses) EngineFailure.ContextLimitExceeded() else null

        else -> if (status == HttpStatusCode.TooManyRequests.value && provider == PiProvider.ZaiCodingPlan.id) {
            piZaiFailure(code)
        } else {
            null
        }
    }
}

/** Extracts only an exact primitive code from the bounded JSON body; parser diagnostics remain redacted. */
@HighFrequency
private fun piProviderCode(message: String): String? {
    val body = message.drop(HTTP_STATUS_LENGTH).trimStart().removePrefix(":").trimStart()
    if (!body.startsWith("{") || body.length > MAX_ERROR_BODY_LENGTH) return null
    val root = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (error: SerializationException) {
        log.w(error.redactedFailure()) { "Pi provider error body could not be classified" }
        null
    } ?: return null
    val error = root["error"] as? JsonObject ?: root
    return error.string("code")
}

/**
 * Z.ai's [HTTP 429 business codes](https://docs.z.ai/api-reference/api-code) distinguish exhausted plans,
 * access restrictions and product keys. Temporary request limits and overload retain the rate-limit fallback.
 */
@HighFrequency
private fun piZaiFailure(code: String?): EngineFailure? = when (code) {
    in ZaiQuotaCodes -> EngineFailure.QuotaExceeded(LimitScope.Unknown)
    "1311" -> EngineFailure.Access(AccessFailureReason.ModelAccessDenied)
    "1313" -> EngineFailure.Access(AccessFailureReason.OperationNotAllowed)
    "1315" -> EngineFailure.Authentication(AuthFailure(AuthFailureReason.AuthMismatch))
    else -> null
}

private val ZaiQuotaCodes = setOf(
    "1113", "1308", "1309", "1310", "1314", "1316", "1317", "1318", "1319", "1320", "1321",
)

private val ContextStatuses = setOf(
    HttpStatusCode.BadRequest.value,
    HttpStatusCode.PayloadTooLarge.value,
    HttpStatusCode.UnprocessableEntity.value,
)
private val LimitStatuses = ContextStatuses + HttpStatusCode.TooManyRequests.value
private const val HTTP_STATUS_LENGTH = 3
private const val MAX_ERROR_BODY_LENGTH = 16_384
