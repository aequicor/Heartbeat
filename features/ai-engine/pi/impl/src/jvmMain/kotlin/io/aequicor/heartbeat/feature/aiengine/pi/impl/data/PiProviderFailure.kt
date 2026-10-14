package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.LimitScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.ktor.http.HttpStatusCode

private val log = Log.tag("PiProviderFailure")

/**
 * Pi 0.87.1 puts SDK failures in assistant.errorMessage, including HTTP bodies. Only a leading HTTP status
 * (SDK `401 ...` or Pi `401: ...`), known structured provider codes and exact SDK transport messages are recognized.
 * Provider-specific business codes are interpreted only for the assistant message's native provider identity.
 * A bare HTTP 413 proves only a rejected payload, not an exhausted model context. Native text never leaves
 * this boundary: it can contain credentials, request headers or the conversation. Unknown formats stay unknown.
 * This is a provisional attempt failure; the session still waits for agent_settled and may recover on retry.
 */
@HighFrequency
internal fun piProviderFailure(message: String?, provider: String? = null): EngineFailure {
    val status = message?.let { ProviderHttpStatus.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    val failure = piProviderLimit(status, message, provider) ?: piHttpFailure(status, message)
    log.w(EngineException(failure)) {
        "Pi provider attempt failed code=${failure.code} status=${status ?: "none"}"
    }
    return failure
}

@HighFrequency
private fun piHttpFailure(status: Int?, message: String?): EngineFailure = when (status) {
    HttpStatusCode.Unauthorized.value ->
        EngineFailure.Authentication(AuthFailure(AuthFailureReason.CredentialsRejected))

    HttpStatusCode.Forbidden.value -> EngineFailure.Access(AccessFailureReason.ModelAccessDenied)

    HttpStatusCode.RequestTimeout.value, HttpStatusCode.GatewayTimeout.value ->
        EngineFailure.Transport(TransportFailureReason.Timeout)

    HttpStatusCode.TooManyRequests.value -> EngineFailure.RateLimited(LimitScope.Unknown)

    in ServerErrors -> EngineFailure.Transport(TransportFailureReason.ServiceUnavailable)

    HttpStatusCode.BadRequest.value, HttpStatusCode.NotFound.value,
    HttpStatusCode.PayloadTooLarge.value, HttpStatusCode.UnprocessableEntity.value,
    ->
        EngineFailure.Request(RequestFailureReason.Invalid)

    else -> piTransportFailure(message)
}

@HighFrequency
private fun piTransportFailure(message: String?): EngineFailure = when (message) {
    "Connection error.", "fetch failed" -> EngineFailure.Transport(TransportFailureReason.NetworkUnavailable)
    "Request timed out." -> EngineFailure.Transport(TransportFailureReason.Timeout)
    "Stream ended without finish_reason" -> EngineFailure.Transport(TransportFailureReason.ProtocolViolation)
    else -> EngineFailure.Unknown()
}

private val ProviderHttpStatus = Regex("^([45][0-9]{2})(?=[:\\s]|$)")
private val ServerErrors = 500..599
