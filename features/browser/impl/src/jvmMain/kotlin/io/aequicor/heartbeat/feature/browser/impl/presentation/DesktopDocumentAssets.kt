package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentAssetResult
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentAssetSession
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentAssets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.cef.callback.CefCallback
import org.cef.callback.CefResourceReadCallback
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host-mediated assets only: Chromium keeps its closed proxy, including WebRTC TCP/UDP. Every request has an
 * independent cancellation handle and shares one bounded loader session for this document's lifetime.
 */
internal class DesktopDocumentAssets(
    assets: BrowserDocumentAssets,
    parent: CoroutineScope,
    dispatcher: CoroutineDispatcher,
) {
    private val session = assets.open()
    private val scope = CoroutineScope(
        parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + dispatcher,
    )
    private val isClosed = AtomicBoolean()
    private val requests = ConcurrentHashMap.newKeySet<AssetResponse>()

    fun response(url: String): CefResourceHandler = AssetResponse(url, session, scope, isClosed::get) {
        requests.remove(it)
    }.also {
        requests += it
        if (isClosed.get()) it.cancel()
    }

    fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        requests.toList().forEach(AssetResponse::cancel)
        scope.cancel()
    }
}

/** Async CEF IO protocol; completion and cancellation race through one gate before touching the native callback. */
private class AssetResponse(
    private val url: String,
    private val session: BrowserDocumentAssetSession,
    private val scope: CoroutineScope,
    private val isClosed: () -> Boolean,
    private val finished: (AssetResponse) -> Unit,
) : CefResourceHandlerAdapter() {
    private val log = Log.tag("DocumentAssetResponse")
    private val isStarted = AtomicBoolean()
    private val isFinished = AtomicBoolean()

    @Volatile
    private var request: Job? = null

    @Volatile
    private var result: BrowserDocumentAssetResult? = null
    private var offset = 0

    override fun open(request: CefRequest, handleRequest: BoolRef, callback: CefCallback): Boolean {
        handleRequest.set(false)
        return start(callback)
    }

    override fun processRequest(request: CefRequest, callback: CefCallback): Boolean = start(callback)

    private fun start(callback: CefCallback): Boolean {
        if (isClosed() || isFinished.get() || !isStarted.compareAndSet(false, true)) return false
        request = scope.launch {
            try {
                val loaded = session.load(url)
                if (isFinished.compareAndSet(false, true)) {
                    result = loaded
                    callback.Continue()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(error.redactedFailure()) { "Document asset response failed" }
            } finally {
                if (isFinished.compareAndSet(false, true)) callback.cancel()
                finished(this@AssetResponse)
            }
        }
        if (isFinished.get()) request?.cancel()
        return true
    }

    override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef) {
        response.setHeaderMap(RESPONSE_HEADERS)
        when (val value = result) {
            is BrowserDocumentAssetResult.Content -> {
                response.status = HTTP_OK
                response.mimeType = value.mimeType
                response.setHeaderMap(value.headers + RESPONSE_HEADERS)
                responseLength.set(value.body.size)
            }

            is BrowserDocumentAssetResult.Redirect -> {
                response.status = HTTP_REDIRECT
                response.setHeaderMap(value.headers + RESPONSE_HEADERS)
                redirectUrl.set(value.url)
                responseLength.set(0)
            }

            null -> {
                response.status = HTTP_NOT_FOUND
                responseLength.set(0)
            }
        }
    }

    override fun read(
        dataOut: ByteArray,
        bytesToRead: Int,
        bytesRead: IntRef,
        callback: CefResourceReadCallback,
    ): Boolean = copy(dataOut, bytesToRead, bytesRead)

    override fun readResponse(
        dataOut: ByteArray,
        bytesToRead: Int,
        bytesRead: IntRef,
        callback: CefCallback,
    ): Boolean = copy(dataOut, bytesToRead, bytesRead)

    private fun copy(dataOut: ByteArray, bytesToRead: Int, bytesRead: IntRef): Boolean {
        val bytes = (result as? BrowserDocumentAssetResult.Content)?.body ?: return false.also { bytesRead.set(0) }
        val count = minOf(bytesToRead, bytes.size - offset)
        bytesRead.set(count)
        if (count <= 0) return false
        bytes.copyInto(dataOut, endIndex = offset + count, startIndex = offset)
        offset += count
        return true
    }

    override fun cancel() {
        isFinished.set(true)
        request?.cancel()
        finished(this)
    }

    private companion object {
        val RESPONSE_HEADERS = mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff")
        const val HTTP_OK = 200
        const val HTTP_REDIRECT = 302
        const val HTTP_NOT_FOUND = 404
    }
}
