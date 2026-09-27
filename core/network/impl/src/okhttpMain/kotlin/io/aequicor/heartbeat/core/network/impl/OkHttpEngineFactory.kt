package io.aequicor.heartbeat.core.network.impl

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Response

/** Leaves retry decisions, attempt limits and logging to Ktor's HttpRequestRetry plugin. */
internal fun createOkHttpEngine(): HttpClientEngine = OkHttp.create {
    config { retryOnConnectionFailure(false) }
    addInterceptor { chain ->
        val attempt = EngineAttempt()
        val request = chain.request().newBuilder().tag(EngineAttempt::class.java, attempt).build()
        chain.proceed(request).restoreResponse(attempt)
    }
    addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.code in EngineRetryStatuses) {
            // OkHttp has no public switch for 503 + Retry-After: 0 or HTTP/2 421 follow-ups.
            // Hide their status only while its internal retry interceptor runs; the outer interceptor
            // restores it before Ktor observes the response. Headers and bodies are never changed.
            checkNotNull(chain.request().tag(EngineAttempt::class.java)).originalStatus = response.code
            response.newBuilder().code(ENGINE_RETRY_BARRIER_STATUS).build()
        } else {
            response
        }
    }
}

/** State belongs to one OkHttp call, including when several calls run concurrently. */
private class EngineAttempt {
    var originalStatus: Int? = null
}

private fun Response.restoreResponse(attempt: EngineAttempt): Response {
    val restored = newBuilder()
        .request(request.newBuilder().tag(EngineAttempt::class.java, null).build())
        .networkResponse(networkResponse?.restoreResponse(attempt))
    attempt.originalStatus?.let { status ->
        if (code == ENGINE_RETRY_BARRIER_STATUS) restored.code(status)
    }
    return restored.build()
}

private const val ENGINE_RETRY_BARRIER_STATUS = 599
private val EngineRetryStatuses = setOf(421, 503)
