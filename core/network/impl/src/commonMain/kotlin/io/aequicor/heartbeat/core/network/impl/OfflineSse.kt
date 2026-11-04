package io.aequicor.heartbeat.core.network.impl

import io.aequicor.heartbeat.core.network.OfflineHttpResponse
import io.ktor.client.plugins.sse.DefaultClientSSESession
import io.ktor.client.plugins.sse.SSEClientContent
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.isSseReconnectionRequest
import io.ktor.client.request.isSseRequest
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlin.coroutines.CoroutineContext

/**
 * Same routing conditions and actual byte parser as Ktor 3.6's SSEClientResponseAdapter. Its default constructor
 * creates an unparented Job; the explicit context keeps acquisition, parser and future children owned by the
 * offline engine. No arbitrary request adapter or synthetic ServerSentEvent producer is invoked.
 */
@OptIn(InternalAPI::class)
internal fun offlineReply(
    request: HttpRequestData,
    response: OfflineHttpResponse.Reply,
    call: CoroutineContext,
    engineJob: Job,
): HttpResponseData {
    val status = HttpStatusCode.fromValue(response.status)
    val headers = Headers.build { response.headers.forEach { (key, value) -> append(key, value) } }
    val bytes = ByteReadChannel(response.body.encodeToByteArray())
    val isSse = request.isSseRequest() && !request.isSseReconnectionRequest() && acceptsSse(status, headers)
    val body = if (isSse) {
        val content = request.body as? SSEClientContent ?: error("Invalid offline SSE request")
        ownedSse(content, bytes, call, engineJob)
    } else {
        bytes
    }
    return HttpResponseData(status, GMTDate(), headers, HttpProtocolVersion.HTTP_1_1, body, call)
}

// Ktor exposes the real parser through this deprecated class; only its three-argument constructor accepts an owner.
@Suppress("DEPRECATION")
@OptIn(InternalAPI::class, InternalCoroutinesApi::class)
private fun ownedSse(content: SSEClientContent, bytes: ByteReadChannel, call: CoroutineContext, engineJob: Job): Any {
    val job = Job(engineJob)
    var isTransferred = false
    try {
        val session = DefaultClientSSESession(content, bytes, call + job)
        // React at cancellation start, not completion: the original call may be waiting for our engine child.
        val link = call.job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) job.cancel()
        }
        job.invokeOnCompletion { link.dispose() }
        isTransferred = true
        return session
    } finally {
        if (!isTransferred) job.cancel()
    }
}

private fun acceptsSse(status: HttpStatusCode, headers: Headers): Boolean {
    val contentType = headers[HttpHeaders.ContentType]?.let(ContentType::parse)
    val isTypeAccepted = contentType == null || contentType.withoutParameters() == ContentType.Text.EventStream
    return status == HttpStatusCode.NoContent || (status == HttpStatusCode.OK && isTypeAccepted)
}
