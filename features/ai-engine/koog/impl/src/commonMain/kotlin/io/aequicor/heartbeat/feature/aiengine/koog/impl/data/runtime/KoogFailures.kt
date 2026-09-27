package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.http.client.KoogHttpClientException
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.LimitScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException

private val log = Log.tag("Koog")

internal fun fail(failure: EngineFailure): Nothing = throw EngineException(failure)

internal fun Exception.sanitized(): EngineException {
    val status = generateSequence<Throwable>(this) { it.cause }.take(MAX_CAUSE_DEPTH).mapNotNull {
        when (it) {
            is KoogHttpClientException -> it.statusCode
            is ResponseException -> it.response.status.value
            else -> null
        }
    }.firstOrNull()
    val failure = when {
        this is EngineException -> failure
        status == UNAUTHORIZED -> EngineFailure.Authentication(AuthFailure(AuthFailureReason.CredentialsRejected))
        status == FORBIDDEN -> EngineFailure.Access(AccessFailureReason.ModelAccessDenied)
        status == RATE_LIMIT -> EngineFailure.RateLimited(LimitScope.Unknown)
        status != null && status >= SERVER_ERROR -> EngineFailure.Transport(TransportFailureReason.ServiceUnavailable)
        else -> EngineFailure.Unknown()
    }
    return EngineException(failure)
}

internal suspend fun <T> koogCall(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    log.w(e.sanitized()) { "Koog operation failed" }
    throw e.sanitized()
}

private const val MAX_CAUSE_DEPTH = 16
private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val RATE_LIMIT = 429
private const val SERVER_ERROR = 500

internal suspend fun <T> koogResult(block: suspend () -> T): Result<T> = try {
    Result.success(koogCall(block))
} catch (e: CancellationException) {
    throw e
} catch (e: EngineException) {
    Result.failure(e)
}
