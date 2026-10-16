package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.api.normalizeBrowserUrl
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserUrlResolver
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.cef.browser.CefRequestContext
import org.cef.handler.CefRequestContextHandler

/**
 * Managed requests use the tab's profile even when a PDF navigation never commits a document.
 * Closing the browser cancels only the transfer: a caller that was not cancelled itself gets
 * IllegalStateException("Browser is closed"). A timeout of [timeout] ms is reported as a failure as well, never as a
 * cancellation.
 */
internal class DesktopBrowserDownload<T>(
    private val dispatchers: DispatcherProvider,
    private val timeout: Long = REQUEST_TIMEOUT,
    private val transfer: suspend (String) -> T,
) {
    private val log = Log.tag("BrowserDownload")
    private val monitor = Any()
    private val operations = mutableSetOf<Job>()
    private var isClosed = false

    /** [transfer] replaces the default one for this call only; it still shares the closure barrier. */
    suspend fun capture(address: String, transfer: suspend (String) -> T = this.transfer): T {
        val url = requireNotNull(normalizeBrowserUrl(address)) { "Invalid source URL" }
        // A supervisor scope keeps the transfer's own cancellation (browser closed) away from the caller's job.
        return supervisorScope {
            val operation = async(dispatchers.io, CoroutineStart.LAZY) { transfer(url) }
            val isOpen = synchronized(monitor) { !isClosed && operations.add(operation) }
            if (!isOpen) operation.cancel()
            check(isOpen) { "Browser is closed" }
            // The caller may cancel while the transfer is still disposing native resources under NonCancellable.
            // Keep it in the profile closure barrier until the transfer itself has finished.
            operation.invokeOnCompletion { synchronized(monitor) { operations.remove(operation) } }
            // join() throws only for the caller's own cancellation, which then also cancels the transfer.
            if (withTimeoutOrNull(timeout) { operation.join() } == null) {
                operation.cancelAndJoin()
                val failure = IllegalStateException("Source transfer timed out")
                log.w(failure) { "Source transfer timed out after ${timeout}ms" }
                throw failure
            }
            check(!operation.isCancelled || !synchronized(monitor) { isClosed }) { "Browser is closed" }
            operation.await()
        }
    }

    fun cancel() {
        val current = synchronized(monitor) {
            isClosed = true
            operations.toList()
        }
        current.forEach { it.cancel() }
    }

    /** Native profile release must await this barrier as well as the browser's onAfterClose. */
    suspend fun awaitClosed() {
        cancel()
        withContext(NonCancellable) {
            synchronized(monitor) { operations.toList() }.forEach { it.join() }
        }
    }

    private companion object {
        const val REQUEST_TIMEOUT = 30_000L
    }
}

/** Source documents of explicit recovery: never cached, up to the document size limit. */
internal fun documentDownload(
    createContext: (CefRequestContextHandler) -> CefRequestContext,
    dispatchers: DispatcherProvider,
    urlResolver: BrowserUrlResolver,
): DesktopBrowserDownload<JsonObject> = DesktopBrowserDownload(dispatchers) {
    DesktopBrowserTransfer(createContext, it, urlResolver, createBody = ::DesktopBrowserDocumentBody).capture()
}

/**
 * Site icons: the profile's cache may answer, the body is a small image, and a slow server fails sooner. Each capture
 * passes its own [iconTransfer] with the tab's destination check; there is no unchecked default.
 */
internal fun iconDownload(dispatchers: DispatcherProvider): DesktopBrowserDownload<ByteArray> =
    DesktopBrowserDownload(dispatchers, ICON_TIMEOUT) { error("Site icons are loaded with a destination check") }

/** One site icon whose every redirect hop must pass [allows]. */
internal suspend fun iconTransfer(
    createContext: (CefRequestContextHandler) -> CefRequestContext,
    url: String,
    urlResolver: BrowserUrlResolver,
    allows: (String) -> Boolean,
): ByteArray = DesktopBrowserTransfer(
    createContext,
    url,
    urlResolver,
    isCacheAllowed = true,
    allows = allows,
    createBody = ::DesktopBrowserIconBody,
).capture()

private const val ICON_TIMEOUT = 10_000L
