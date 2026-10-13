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
 * (SDK `401 ...` or Pi `401: ...`) and exact SDK transport messages are recognized. Native text never leaves
 * this boundary: it can contain credentials, request headers or the conversation. Unknown formats stay unknown.
 * This is a provisional attempt failure; the session still waits for agent_settled and may recover on retry.
 */
@HighFrequency
internal fun piProviderFailure(message: String?): EngineFailure {
    val status = message?.let { ProviderHttpStatus.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    val failure = when (status) {
        HttpStatusCode.Unauthorized.value ->
            EngineFailure.Authentication(AuthFailure(AuthFailureReason.CredentialsRejected))

        HttpStatusCode.Forbidden.value -> EngineFailure.Access(AccessFailureReason.ModelAccessDenied)

        HttpStatusCode.RequestTimeout.value, HttpStatusCode.GatewayTimeout.value ->
            EngineFailure.Transport(TransportFailureReason.Timeout)

        HttpStatusCode.PayloadTooLarge.value -> EngineFailure.ContextLimitExceeded()

        HttpStatusCode.TooManyRequests.value -> EngineFailure.RateLimited(LimitScope.Unknown)

        in ServerErrors -> EngineFailure.Transport(TransportFailureReason.ServiceUnavailable)

        HttpStatusCode.BadRequest.value, HttpStatusCode.NotFound.value, HttpStatusCode.UnprocessableEntity.value ->
            EngineFailure.Request(RequestFailureReason.Invalid)

        else -> piTransportFailure(message)
    }
    log.w(EngineException(failure)) {
        "Pi provider attempt failed code=${failure.code} status=${status ?: "none"}"
    }
    return failure
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
