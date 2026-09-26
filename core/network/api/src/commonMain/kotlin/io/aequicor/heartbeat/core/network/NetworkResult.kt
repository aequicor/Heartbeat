package io.aequicor.heartbeat.core.network

import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

/**
 * Runs a network call (request + body decoding) and returns its expected failures as [NetworkException]:
 * non-2xx status, timeouts, connection failures, an undecodable response body.
 *
 * - [CancellationException] is rethrown: the caller was cancelled.
 * - Anything else is a bug and is rethrown as is — including a request body that cannot be serialized.
 * - Nothing is logged here: the transport logs every request, the repository logs the failure it handles.
 *
 * ```
 * suspend fun list(): Result<List<ProjectDto>> = networkResult { client.get("$baseUrl/projects").body() }
 * ```
 */
public suspend fun <T> networkResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: ResponseException) {
    Result.failure(NetworkException.Http(e.response.status))
} catch (e: IOException) {
    Result.failure(e.toNetworkException())
} catch (e: ContentConvertException) {
    // thrown by `body()` for malformed content; request serialization failures are other types
    Result.failure(NetworkException.InvalidResponse(e.origin))
} catch (e: NoTransformationFoundException) {
    Result.failure(NetworkException.InvalidResponse(e.origin))
}

private fun IOException.toNetworkException(): NetworkException = when (this) {
    is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException ->
        NetworkException.Timeout(origin)

    else -> NetworkException.Connectivity(origin)
}

private val Throwable.origin: String get() = this::class.simpleName ?: "unknown"
