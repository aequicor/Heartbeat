package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserUrlResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.cef.browser.CefRequestContext
import org.cef.callback.CefAuthCallback
import org.cef.callback.CefNativeAdapter
import org.cef.callback.CefURLRequestClient
import org.cef.handler.CefRequestContextHandler
import org.cef.network.CefRequest
import org.cef.network.CefURLRequest
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlin.coroutines.resume

/**
 * Explicit redirect hops share one profile wrapper and bounded lifetime, without automatic redirect IO. [createBody]
 * bounds and interprets the final response; only [isCacheAllowed] transfers may be answered from the profile's cache.
 * Every hop must also pass the caller's `allows`.
 */
internal class DesktopBrowserTransfer<T>(
    private val createContext: (CefRequestContextHandler) -> CefRequestContext,
    url: String,
    urlResolver: BrowserUrlResolver,
    private val isCacheAllowed: Boolean = false,
    allows: (String) -> Boolean = { true },
    private val createBody: (reject: (String) -> Unit) -> DesktopBrowserResponseBody<T>,
) {
    private val log = Log.tag("BrowserTransfer")
    private val policy = DesktopBrowserTransferPolicy(url, urlResolver, allows)
    private val downloadedBytes = AtomicLong()

    suspend fun capture(): T {
        val context = createContext(policy)
        try {
            while (true) captureHop(context)?.let { return it }
        } finally {
            withContext(NonCancellable) {
                awaitNativeCallback(NATIVE_CALLBACK_TIMEOUT, log, "Source request context disposal") { done ->
                    context.dispose { done() }
                }
            }
        }
    }

    private suspend fun captureHop(context: CefRequestContext): T? {
        val input = CefRequest.create()
        val client = Client()
        var request: CefURLRequest? = null
        try {
            input.setURL(policy.currentUrl)
            input.method = "GET"
            input.firstPartyForCookies = policy.currentUrl
            val cache = if (isCacheAllowed) 0 else CefRequest.CefUrlRequestFlags.UR_FLAG_SKIP_CACHE
            input.flags = cache or
                CefRequest.CefUrlRequestFlags.UR_FLAG_ALLOW_STORED_CREDENTIALS or
                CefRequest.CefUrlRequestFlags.UR_FLAG_STOP_ON_REDIRECT
            request = checkNotNull(CefURLRequest.create(input, client, context)) {
                "Browser request could not start"
            }
            select {
                client.rejected.onAwait { throw DesktopBrowserSourceRejected(it) }
                client.completed.onAwait { }
            }
            check(!client.rejected.isCompleted) { "Source request was rejected" }
            return response(request, client.body)
        } finally {
            withContext(NonCancellable) { release(request, input, client) }
        }
    }

    /**
     * The native request is disposed only after its completion callback. A missing callback no longer holds the
     * caller (and the tab lock it owns): the coroutine continues and disposal waits for a late callback.
     */
    private suspend fun release(request: CefURLRequest?, input: CefRequest, client: Client) {
        if (request == null) {
            input.dispose()
            return
        }
        if (!client.completed.isCompleted) request.cancel()
        val isCompleted = withTimeoutOrNull(NATIVE_CALLBACK_TIMEOUT) { client.completed.await() } != null
        if (isCompleted) {
            request.dispose()
            input.dispose()
        } else {
            log.w(IllegalStateException("Source request completion timed out")) {
                "Native source request did not complete after cancellation; disposal deferred"
            }
            // Queued after the native callback returns: callbacks never reenter native request methods.
            client.completed.invokeOnCompletion {
                SwingUtilities.invokeLater {
                    request.dispose()
                    input.dispose()
                }
            }
        }
    }

    private fun response(request: CefURLRequest, body: DesktopBrowserResponseBody<T>): T? {
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
        val body = createBody { rejected.complete(it) }

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
        const val NATIVE_CALLBACK_TIMEOUT = 10_000L
    }
}

/**
 * Waits up to [timeout] for a native completion callback that [register] hands to native code. Returns false
 * after a warning when it does not arrive: the caller is released and a late callback is ignored.
 */
internal suspend fun awaitNativeCallback(
    timeout: Long,
    log: Log,
    operation: String,
    register: (done: () -> Unit) -> Unit,
): Boolean {
    val isDone = withTimeoutOrNull(timeout) {
        suspendCancellableCoroutine { continuation ->
            register { if (continuation.isActive) continuation.resume(Unit) }
        }
    } != null
    if (!isDone) log.w(IllegalStateException("$operation timed out")) { "$operation callback did not arrive in time" }
    return isDone
}
