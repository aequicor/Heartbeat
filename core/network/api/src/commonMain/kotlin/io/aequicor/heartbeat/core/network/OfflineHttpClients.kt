package io.aequicor.heartbeat.core.network

import io.ktor.client.HttpClient

/**
 * Creates an owned, socket-free HTTP transport for offline protocol checks. It has no platform engine,
 * delegate, callback or fallback. Only finite scripted exchanges can produce responses.
 * Fixtures must be synthetic: never pass credentials, user prompts or history.
 */
public interface OfflineHttpClients {
    /**
     * Snapshots and validates the script before allocating a transport. At most 16 exchanges, 64 KiB of UTF-8
     * body per request/response, 32 headers per side, 4 KiB per URL/header and 1 MiB total are accepted.
     * Invalid scripts throw a fixed-message IllegalArgumentException without fixture values.
     */
    public suspend fun open(script: List<OfflineHttpExchange>): OfflineHttpSession
}

/** One expected HTTP request and its sole possible response. Bodies match exact UTF-8 bytes. */
public data class OfflineHttpExchange(
    /** Exact case-sensitive HTTP method. */
    val method: String,
    /** Exact absolute HTTP(S) URL, including query and path. No DNS lookup occurs. */
    val url: String,
    /** Required header values; names match case-insensitively. Other headers are permitted. */
    val headers: Map<String, String> = emptyMap(),
    /** Exact serialized request body. Streaming request bodies are refused without being read. */
    val body: String = "",
    /** Fixed response or a cancellable wait without producing headers. */
    val response: OfflineHttpResponse,
)

/** Bounded local response bytes or a cancellable wait; none of the variants can perform IO. */
public sealed interface OfflineHttpResponse {
    /** Fixed response bytes, also usable as an SSE/NDJSON byte stream by the caller's real codec. */
    public data class Reply(
        /** HTTP status in 100..599. */
        val status: Int = 200,
        /** Response headers; invalid names and values are refused before transport allocation. */
        val headers: Map<String, String> = emptyMap(),
        /** Complete UTF-8 response bytes; a deliberately truncated protocol message is permitted. */
        val body: String,
    ) : OfflineHttpResponse

    /** Produces no headers or bytes; caller cancellation, the client's timeout or session closure ends the wait. */
    public data object AwaitCancellation : OfflineHttpResponse
}

/** Counts only, without request/response values. A failed match permanently prevents [isComplete]. */
public data class OfflineHttpProgress(
    /** Number of scripted requests. */
    val expected: Int,
    /** Number of requests received by the offline engine, including unexpected requests. */
    val received: Int,
    /** Whether all received requests matched their next exchange. */
    val isMatched: Boolean,
) {
    /** All expected requests arrived without an extra/mismatched request; not proof of codec correctness. */
    public val isComplete: Boolean get() = isMatched && received == expected
}

/** Owns the root client and its offline engine. Close SDK-derived clients before closing this session. */
public interface OfflineHttpSession : AutoCloseable {
    /** Fresh client with common network logging/redaction, no retries and no redirects. */
    public val client: HttpClient

    /** A concurrency-safe snapshot. No request bodies or headers are retained. */
    public suspend fun progress(): OfflineHttpProgress

    /** Cancels pending offline requests and starts closing the root client and engine; idempotent. */
    override fun close()

    /** Closes and joins both owned jobs even when the caller is cancelled. */
    public suspend fun closeAndAwait()
}
