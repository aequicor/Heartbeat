package io.aequicor.heartbeat.feature.browser.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.SensitiveNetworkRequest
import io.aequicor.heartbeat.core.network.networkResult
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentAssetResult
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentAssetSession
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentAssets
import io.ktor.client.HttpClient
import io.ktor.client.plugins.retry
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Shares the application's engine and logging, but has a separate anonymous client and profile lifetime. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class HttpBrowserDocumentAssets(
    client: HttpClient,
    private val dispatchers: DispatcherProvider,
    @ForScope(ProfileScope::class) scope: ScopeHandle,
) : BrowserDocumentAssets {
    private val isClosed = AtomicBoolean(false)
    private val client = client.documentAssetClient()

    init {
        scope.onClose {
            isClosed.set(true)
            this.client.coroutineContext.cancel()
            this.client.close()
        }
    }

    override fun open(): BrowserDocumentAssetSession {
        val session = HttpDocumentAssetSession(client) { !isClosed.get() }
        return BrowserDocumentAssetSession { url ->
            if (isClosed.get()) null else withContext(dispatchers.io) { session.load(url) }
        }
    }
}

/** Budgets include failed transfers and redirect hops; concurrent calls cannot reset or overspend them. */
private class HttpDocumentAssetSession(private val client: HttpClient, private val isAvailable: () -> Boolean) :
    BrowserDocumentAssetSession {
    private val log = Log.tag("DocumentAssets")
    private val monitor = Any()
    private val parallel = Semaphore(MAX_PARALLEL)
    private var requests = 0
    private var redirects = 0
    private var receivedBytes = 0

    override suspend fun load(url: String): BrowserDocumentAssetResult? {
        val requested = documentAssetUrl(url) ?: return null
        if (!reserveRequest()) return null
        // Includes time waiting for a permit. withTimeoutOrNull only absorbs its own timeout, never the caller's.
        return withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
            parallel.withPermit {
                if (!isAvailable()) return@withPermit null
                networkResult {
                    client.prepareGet(requested) {
                        attributes.put(DocumentAssetUrl, requested)
                        attributes.put(SensitiveNetworkRequest, Unit)
                        retry { noRetry() }
                    }.execute { response -> receive(response, requested) }
                }.fold(
                    onSuccess = { it },
                    onFailure = { error ->
                        log.w(error) { "Document asset request failed" }
                        null
                    },
                )
            }
        }
    }

    private suspend fun receive(response: HttpResponse, requested: Url): BrowserDocumentAssetResult? {
        if (response.status.value in REDIRECT_STATUSES) {
            val location = response.headers[HttpHeaders.Location] ?: return null
            val redirected = documentAssetUrl(location, requested) ?: return null
            return if (reserveRedirect()) {
                BrowserDocumentAssetResult.Redirect(redirected.toString(), documentAssetCorsHeaders(response.headers))
            } else {
                null
            }
        }
        if (!response.status.isSuccess()) return null
        val metadata = documentAssetMetadata(response.headers) ?: return null
        val announced = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (announced != null && (announced < 0 || announced > MAX_RESOURCE_BYTES)) return null
        val body = readBody(response) ?: return null
        if (announced != null && body.size.toLong() != announced) return null
        return BrowserDocumentAssetResult.Content(body, metadata.mimeType, metadata.headers)
    }

    @HighFrequency
    private suspend fun readBody(response: HttpResponse): ByteArray? {
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(BUFFER_BYTES)
        val output = ByteArrayOutputStream()
        while (true) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count < 0) return output.toByteArray()
            if (!reserveBytes(count) || output.size() + count > MAX_RESOURCE_BYTES) return null
            output.write(buffer, 0, count)
        }
    }

    private fun reserveRequest(): Boolean = synchronized(monitor) {
        if (requests >= MAX_REQUESTS || receivedBytes >= MAX_TOTAL_BYTES) {
            false
        } else {
            requests++
            true
        }
    }

    private fun reserveRedirect(): Boolean = synchronized(monitor) {
        if (redirects >= MAX_REDIRECTS) {
            false
        } else {
            redirects++
            true
        }
    }

    @HighFrequency
    private fun reserveBytes(count: Int): Boolean = synchronized(monitor) {
        val hasBudget = count <= MAX_TOTAL_BYTES - receivedBytes
        receivedBytes = minOf(MAX_TOTAL_BYTES, receivedBytes + count)
        hasBudget
    }

    private companion object {
        const val MAX_RESOURCE_BYTES = 8 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
        const val MAX_REQUESTS = 128
        const val MAX_REDIRECTS = 16
        const val MAX_PARALLEL = 4
        const val BUFFER_BYTES = 16 * 1024
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}
