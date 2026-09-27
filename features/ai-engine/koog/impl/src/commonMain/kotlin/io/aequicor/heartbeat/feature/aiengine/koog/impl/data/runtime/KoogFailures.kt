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
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

private val log = Log.tag("Koog")

internal fun fail(failure: EngineFailure): Nothing = throw EngineException(failure)

/**
 * Maps a native failure to an established contract cause. Response bodies are inspected only for provider error
 * codes and never leave this function; the result carries no native cause.
 */
internal fun Exception.sanitized(): EngineException {
    if (this is EngineException) return EngineException(failure)
    val causes = generateSequence<Throwable>(this) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
    val http = causes.firstNotNullOfOrNull { it.httpError() }
    val failure = when {
        http != null -> http.failure()
        causes.any { it.isTimeout() } -> EngineFailure.Transport(TransportFailureReason.Timeout)
        causes.any { it is IOException } -> EngineFailure.Transport(TransportFailureReason.NetworkUnavailable)
        else -> EngineFailure.Unknown()
    }
    return EngineException(failure)
}

private data class HttpError(val status: Int, val body: String)

private fun Throwable.httpError(): HttpError? = when (this) {
    is KoogHttpClientException -> statusCode?.let { HttpError(it, errorBody.orEmpty()) }
    is ResponseException -> HttpError(response.status.value, "")
    else -> null
}

private fun Throwable.isTimeout(): Boolean =
    this is HttpRequestTimeoutException || this is ConnectTimeoutException || this is SocketTimeoutException

private fun HttpError.failure(): EngineFailure = when {
    status == UNAUTHORIZED -> EngineFailure.Authentication(AuthFailure(AuthFailureReason.CredentialsRejected))

    status == FORBIDDEN -> EngineFailure.Access(AccessFailureReason.ModelAccessDenied)

    status == PAYLOAD_TOO_LARGE || ContextErrors.any { body.contains(it, ignoreCase = true) } ->
        EngineFailure.ContextLimitExceeded()

    status == RATE_LIMIT && QuotaErrors.any { body.contains(it, ignoreCase = true) } ->
        EngineFailure.QuotaExceeded(LimitScope.Unknown)

    status == RATE_LIMIT -> EngineFailure.RateLimited(LimitScope.Unknown)

    status >= SERVER_ERROR -> EngineFailure.Transport(TransportFailureReason.ServiceUnavailable)

    else -> EngineFailure.Unknown()
}

/** Logs the native failure class and status only, then throws its sanitized form. */
internal suspend fun <T> koogCall(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw e.sanitized().also { safe ->
        val status = generateSequence<Throwable>(e) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .firstNotNullOfOrNull { it.httpError() }
        log.w(safe) { "Koog operation failed: ${e::class.simpleName.orEmpty()}, status=${status?.status ?: "none"}" }
    }
}

internal suspend fun <T> koogResult(block: suspend () -> T): Result<T> = try {
    Result.success(koogCall(block))
} catch (e: CancellationException) {
    throw e
} catch (e: EngineException) {
    Result.failure(e)
}

/** OpenAI `context_length_exceeded`, Anthropic "prompt is too long". */
private val ContextErrors = listOf("context_length_exceeded", "prompt is too long")

/** OpenAI reports an exhausted balance as 429 `insufficient_quota`. */
private val QuotaErrors = listOf("insufficient_quota")

private const val MAX_CAUSE_DEPTH = 16
private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val PAYLOAD_TOO_LARGE = 413
private const val RATE_LIMIT = 429
private const val SERVER_ERROR = 500
