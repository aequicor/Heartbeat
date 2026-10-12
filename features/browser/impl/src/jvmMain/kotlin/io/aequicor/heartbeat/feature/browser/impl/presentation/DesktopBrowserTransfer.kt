package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserUrlResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.cef.browser.CefRequestContext
import org.cef.callback.CefAuthCallback
import org.cef.callback.CefNativeAdapter
import org.cef.callback.CefURLRequestClient
import org.cef.handler.CefRequestContextHandler
import org.cef.network.CefRequest
import org.cef.network.CefURLRequest
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/** Explicit redirect hops share one profile wrapper and bounded lifetime, without automatic redirect IO. */
internal class DesktopBrowserTransfer(
    private val createContext: (CefRequestContextHandler) -> CefRequestContext,
    url: String,
    urlResolver: BrowserUrlResolver,
) {
    private val log = Log.tag("BrowserTransfer")
    private val policy = DesktopBrowserTransferPolicy(url, urlResolver)
    private val downloadedBytes = AtomicLong()

    suspend fun capture(): JsonObject {
        val context = createContext(policy)
        try {
            while (true) captureHop(context)?.let { return it }
        } finally {
            withContext(NonCancellable) {
                suspendCancellableCoroutine { continuation ->
                    context.dispose { continuation.resume(Unit) }
                }
            }
        }
    }

    private suspend fun captureHop(context: CefRequestContext): JsonObject? {
        val input = CefRequest.create()
        val client = Client()
        var request: CefURLRequest? = null
        try {
            input.setURL(policy.currentUrl)
            input.method = "GET"
            input.firstPartyForCookies = policy.currentUrl
            input.flags = CefRequest.CefUrlRequestFlags.UR_FLAG_SKIP_CACHE or
                CefRequest.CefUrlRequestFlags.UR_FLAG_ALLOW_STORED_CREDENTIALS or
                CefRequest.CefUrlRequestFlags.UR_FLAG_STOP_ON_REDIRECT
            request = checkNotNull(CefURLRequest.create(input, client, context)) {
                "Browser request could not start"
            }
            select {
                client.rejected.onAwait { error(it) }
                client.completed.onAwait { }
            }
            check(!client.rejected.isCompleted) { "Source request was rejected" }
            return response(request, client.body)
        } finally {
            withContext(NonCancellable) {
                request?.let {
                    if (!client.completed.isCompleted) it.cancel()
                    client.completed.await()
                    it.dispose()
                }
                input.dispose()
            }
        }
    }

    private fun response(request: CefURLRequest, body: DesktopBrowserDocumentBody): JsonObject? {
        val response = checkNotNull(request.response) { "Source response is unavailable" }
        return try {
            val isFinal = policy.accept(
                response.status,
                request.requestStatus,
                request.requestError,
                response.getHeaderByName("Location"),
            )
            if (isFinal) {
                val mime = response.mimeType.orEmpty().substringBefore(';').trim().lowercase()
                body.result(policy.finalUrl(), mime)
            } else {
                null
            }
        } finally {
            response.dispose()
        }
    }

    private inner class Client :
        CefNativeAdapter(),
        CefURLRequestClient {
        val completed = CompletableDeferred<Unit>()
        val rejected = CompletableDeferred<String>()
        val body = DesktopBrowserDocumentBody { rejected.complete(it) }

        override fun onRequestComplete(request: CefURLRequest) {
            log.v { "Managed source request completed" }
            completed.complete(Unit)
        }

        @HighFrequency
        override fun onUploadProgress(request: CefURLRequest, current: Int, total: Int) {
            log.v { "Managed request upload progress" }
        }

        @HighFrequency
        override fun onDownloadProgress(request: CefURLRequest, current: Int, total: Int) {
            log.v { "Managed request download progress" }
            body.progress(current, total)
        }

        @HighFrequency
        override fun onDownloadData(request: CefURLRequest, data: ByteArray, dataLength: Int) {
            log.v { "Managed request chunk received" }
            if (dataLength < 0 || downloadedBytes.addAndGet(dataLength.toLong()) > MAX_BYTES) {
                rejected.complete("Source document exceeds size limit")
            } else {
                body.append(data, dataLength)
            }
        }

        override fun getAuthCredentials(
            isProxy: Boolean,
            host: String?,
            port: Int,
            realm: String?,
            scheme: String?,
            callback: CefAuthCallback,
        ): Boolean {
            log.v { "Managed source request authentication declined" }
            return false
        }
    }

    private companion object {
        const val MAX_BYTES = 10_000_000
    }
}
