package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.http.client.KoogHttpClient
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass

/** Observes SSE usage on the existing transport without another request or changing SDK decoding. */
internal class UsageKoogHttpFactory(
    private val delegate: KoogHttpClient.Factory,
    private val usage: KoogUsageCapture,
) : KoogHttpClient.Factory {
    override fun create(
        clientName: String,
        baseUrl: String,
        headers: Map<String, String>,
        queryParameters: Map<String, String>,
        requestTimeoutMillis: Long,
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        json: Json,
    ): KoogHttpClient = UsageKoogHttpClient(
        delegate.create(
            clientName,
            baseUrl,
            headers,
            queryParameters,
            requestTimeoutMillis,
            connectTimeoutMillis,
            socketTimeoutMillis,
            json,
        ),
        usage,
    )
}

private class UsageKoogHttpClient(private val delegate: KoogHttpClient, private val usage: KoogUsageCapture) :
    KoogHttpClient by delegate {
    private val log = Log.tag("KoogUsageHttp")
    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<O> {
        log.d { "Starting provider SSE stream" }
        return delegate.sse(
            path,
            requestBody,
            requestBodyType,
            dataFilter,
            { data ->
                usage.anthropic(data)
                decodeStreamingResponse(data)
            },
            processStreamingChunk,
            parameters,
            headers,
        ).onStart { usage.reset() }
    }
}
