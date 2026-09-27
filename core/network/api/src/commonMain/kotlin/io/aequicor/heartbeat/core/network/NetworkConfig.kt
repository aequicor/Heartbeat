package io.aequicor.heartbeat.core.network

/**
 * Transport settings of the application [io.ktor.client.HttpClient]. Endpoints (base URLs) are not here:
 * each feature API takes its own from DI.
 *
 * The default instance is used unless the graph provides another `NetworkConfig`. A single request
 * overrides them with Ktor's `timeout { }` / `retry { }` in its request builder.
 */
public data class NetworkConfig(
    /** Whole call: from sending the request to receiving the response (a non-streaming call reads the body in it). */
    val requestTimeoutMillis: Long = 30_000,
    /** Establishing a connection. */
    val connectTimeoutMillis: Long = 10_000,
    /** Maximum inactivity between two data packets. */
    val socketTimeoutMillis: Long = 30_000,
    /**
     * Retries of idempotent requests (`GET`, `HEAD`, `OPTIONS`, `PUT`, `DELETE`) after a connection failure
     * or a 5xx response, with exponential backoff. Only byte-array or absent bodies are replayed; streaming bodies
     * and `POST`/`PATCH` are never retried automatically.
     */
    val maxRetries: Int = 2,
) {
    init {
        require(requestTimeoutMillis > 0) { "requestTimeoutMillis must be positive" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(socketTimeoutMillis > 0) { "socketTimeoutMillis must be positive" }
        require(maxRetries >= 0) { "maxRetries must not be negative" }
    }
}
