package io.aequicor.heartbeat.core.network.impl

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.OfflineHttpExchange
import io.aequicor.heartbeat.core.network.OfflineHttpProgress
import io.aequicor.heartbeat.core.network.OfflineHttpResponse
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.engine.mergeHeaders
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A finite byte responder, not a wrapper around any platform engine. There is no network-capable dependency. */
internal class ScriptedOfflineEngine(private val script: List<OfflineHttpExchange>, dispatcher: CoroutineDispatcher) :
    HttpClientEngineBase("heartbeat-offline") {
    override val config = HttpClientEngineConfig().apply { this.dispatcher = dispatcher }
    override val supportedCapabilities = setOf(HttpTimeoutCapability)
    private val mutex = Mutex()
    private var received = 0
    private var isMatched = true

    suspend fun progress(): OfflineHttpProgress = mutex.withLock {
        OfflineHttpProgress(script.size, received, isMatched)
    }

    @OptIn(InternalAPI::class)
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val exchange = mutex.withLock {
            val next = script.getOrNull(received)
            received++
            val isPreviouslyMatched = isMatched
            isMatched = false
            if (!isPreviouslyMatched || next == null || !matches(next, data)) error("Offline HTTP script mismatch")
            isMatched = true
            next
        }
        return when (val response = exchange.response) {
            OfflineHttpResponse.AwaitCancellation -> awaitCancellation()

            is OfflineHttpResponse.Reply -> HttpResponseData(
                HttpStatusCode.fromValue(response.status),
                GMTDate(),
                Headers.build { response.headers.forEach { (key, value) -> append(key, value) } },
                HttpProtocolVersion.HTTP_1_1,
                ByteReadChannel(response.body.encodeToByteArray()),
                callContext(),
            )
        }
    }
}

private fun matches(expected: OfflineHttpExchange, actual: HttpRequestData): Boolean = try {
    actual.method.value == expected.method && actual.url.toString() == expected.url &&
        requestBytes(actual.body)?.contentEquals(expected.body.encodeToByteArray()) == true &&
        matchesHeaders(expected, actual)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    val safe = IllegalStateException("Offline HTTP script mismatch")
    Log.tag("OfflineHttpClients").w(safe) { "Offline body extraction failed: ${e::class.simpleName.orEmpty()}" }
    throw safe
}

/** Match the same effective headers that a platform engine would render, including body overrides. */
@OptIn(InternalAPI::class)
private fun matchesHeaders(expected: OfflineHttpExchange, actual: HttpRequestData): Boolean {
    val effective = Headers.build { mergeHeaders(actual.headers, actual.body) { key, value -> append(key, value) } }
    return expected.headers.all { (key, value) -> effective[key] == value }
}

/** Refuse channels and excessive wrappers without reading them or starting a producer. */
private fun requestBytes(body: OutgoingContent): ByteArray? {
    var content = body
    var depth = 0
    while (content is OutgoingContent.ContentWrapper && depth < MAX_OFFLINE_WRAPPERS) {
        content = content.delegate()
        depth++
    }
    return when (val current = content) {
        is OutgoingContent.NoContent -> byteArrayOf()
        is OutgoingContent.ByteArrayContent -> current.bytes().takeIf { it.size <= MAX_OFFLINE_BODY_BYTES }
        else -> null
    }
}

private const val MAX_OFFLINE_WRAPPERS = 8
