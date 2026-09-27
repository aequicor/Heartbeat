package io.aequicor.heartbeat.core.network.impl

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.NetworkConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.utils.unwrapCancellationException
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.io.IOException
import kotlinx.serialization.json.Json

/**
 * The application client. Plugin order matters: every retry attempt gets its own timeout and its own log line,
 * because [NetworkLogging] is installed after [HttpRequestRetry] and [HttpTimeout] (inner `Send` interceptor).
 *
 * - non-2xx responses throw `ResponseException` (`expectSuccess`); `networkResult { }` maps it;
 * - only idempotent methods with replayable bodies are retried: on connection failures (not timeouts) and 5xx;
 * - every failure is logged once at `W`, by its class name (Ktor exception messages carry the URL and bodies);
 * - bodies are never logged.
 *
 * @param engine platform engine (`<Platform>EngineBindings`) or `MockEngine` in tests; the caller closes it.
 * @param config timeouts and retry limit.
 * @param retryDelay waits between retries; tests replace it to avoid real delays.
 */
internal fun createHttpClient(
    engine: HttpClientEngine,
    config: NetworkConfig,
    retryDelay: suspend (Long) -> Unit = { delay(it) },
): HttpClient = HttpClient(engine) {
    expectSuccess = true

    install(ContentNegotiation) { json(NetworkJson) }

    install(ResponseHeaderValidation)

    install(HttpRequestRetry) {
        maxRetries = config.maxRetries
        retryIf { request, response ->
            request.method in IdempotentMethods && request.content.isReplayableBody() &&
                response.status.value in ServerErrors
        }
        retryOnExceptionIf { request, cause ->
            request.method in IdempotentMethods && request.body.isReplayableBody() && cause.isRetryableFailure()
        }
        exponentialDelay(respectRetryAfterHeader = false)
        delay(retryDelay)
        modifyRequest { request ->
            log.i { "retry #$retryCount ${request.method.value} ${request.url.build().forLog()}" }
        }
    }

    install(HttpTimeout) {
        requestTimeoutMillis = config.requestTimeoutMillis
        connectTimeoutMillis = config.connectTimeoutMillis
        socketTimeoutMillis = config.socketTimeoutMillis
    }

    install(NetworkLogging)

    // Outermost: sees the request timeout unwrapped from the cancellation that carries it through NetworkLogging.
    // Connect/socket timeouts are plain IOExceptions — NetworkLogging already logged them as failures.
    HttpResponseValidator {
        handleResponseExceptionWithRequest { cause, request ->
            if (cause is HttpRequestTimeoutException) {
                log.w { "${request.method.value} ${request.url.forLog()} timed out" }
            }
        }
    }
}

private val log = Log.tag(NET_LOG_TAG)

private val NetworkJson = Json { ignoreUnknownKeys = true }

private val IdempotentMethods =
    setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options, HttpMethod.Put, HttpMethod.Delete)

private val ServerErrors = 500..599

/** Send interceptors see transformed content; channel-backed bodies may already have been consumed. */
private fun Any.isReplayableBody(): Boolean = when (this) {
    is OutgoingContent.ByteArrayContent, is OutgoingContent.NoContent -> true
    is OutgoingContent.ContentWrapper -> delegate().isReplayableBody()
    else -> false
}

/** Connection failures only: timeouts are not retried (the user already waited), cancellation and bugs never. */
private fun Throwable.isRetryableFailure(): Boolean {
    val cause = unwrapCancellationException()
    return cause is IOException && !cause.isTimeout()
}

private fun Throwable.isTimeout(): Boolean =
    this is HttpRequestTimeoutException || this is ConnectTimeoutException || this is SocketTimeoutException
