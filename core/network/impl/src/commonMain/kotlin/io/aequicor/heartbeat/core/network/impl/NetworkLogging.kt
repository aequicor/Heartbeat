package io.aequicor.heartbeat.core.network.impl

import io.aequicor.heartbeat.core.logging.Log
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.Headers
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource

internal const val NET_LOG_TAG = "NET"

/**
 * Logs every attempt of every request (tag `NET`):
 * - `I` — method, URL (query values hidden), status, duration; non-2xx and failures — `W`;
 * - `D` — request and response headers, values of sensitive ones hidden.
 *
 * Bodies are never logged: they carry user data and prompts. Neither are Ktor exceptions: their messages hold the full
 * URL (`[url=…]`) and, for responses, the body — a failure is logged by its class name; the caller gets the exception.
 */
internal val NetworkLogging = createClientPlugin("NetworkLogging") {
    val log = Log.tag(NET_LOG_TAG)

    on(Send) { request ->
        val method = request.method.value
        val url = request.url.build().forLog()
        log.d { "--> $method $url headers: ${request.headers.build().forLog()}" }
        val started = TimeSource.Monotonic.markNow()
        var isCompleted = false
        try {
            val call = proceed(request)
            isCompleted = true
            val status = call.response.status
            val ms = started.elapsedNow().inWholeMilliseconds
            if (status.isSuccess()) {
                log.i { "$method $url -> ${status.value} ($ms ms)" }
            } else {
                log.w { "$method $url -> ${status.value} ($ms ms)" }
            }
            log.d { "<-- $method $url headers: ${call.response.headers.forLog()}" }
            call
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            isCompleted = true
            val type = e::class.simpleName ?: "Exception"
            log.w { "$method $url failed: $type (${started.elapsedNow().inWholeMilliseconds} ms)" }
            throw e
        } finally {
            // Cancellation of the caller, or the request timeout: Ktor delivers the latter here as a
            // CancellationException; the response validator logs it as "timed out" (see createHttpClient).
            if (!isCompleted) log.i { "$method $url interrupted (${started.elapsedNow().inWholeMilliseconds} ms)" }
        }
    }
}

/** `https://host:port/path?q=***`: query values may carry user data. */
internal fun Url.forLog(): String = buildString {
    append(protocol.name).append("://").append(host)
    if (port != protocol.defaultPort) append(':').append(port)
    append(encodedPath)
    val names = parameters.names()
    if (names.isNotEmpty()) append(names.sorted().joinToString("&", prefix = "?") { "$it=***" })
}

/** Authentication and URL-bearing header values are replaced by `***`. */
internal fun Headers.forLog(): String = entries().sortedBy { it.key.lowercase() }.joinToString { (name, values) ->
    val shown = if (name.isSensitiveHeader()) Log.redact(values) else values.joinToString(",")
    "$name=$shown"
}

private fun String.isSensitiveHeader(): Boolean {
    val name = lowercase()
    return name in SensitiveHeaders || SensitiveHeaderParts.any { it in name }
}

private val SensitiveHeaders = setOf(
    "authorization",
    "proxy-authorization",
    "cookie",
    "set-cookie",
    // URLs can carry credentials in queries, user-info, and fragments; hide the whole value,
    // including relative references and compound Link/Refresh header syntax.
    "location",
    "content-location",
    "referer",
    "link",
    "refresh",
)
private val SensitiveHeaderParts = listOf("token", "secret", "key", "session", "auth")
