package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.browser.api.normalizeBrowserUrl
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserUrlResolver
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.cef.browser.CefRequestContext
import org.cef.handler.CefRequestContextHandler

/** Managed requests use the tab's profile even when a PDF navigation never commits a document. */
internal class DesktopBrowserDownload(
    private val dispatchers: DispatcherProvider,
    private val transfer: suspend (String) -> JsonObject,
) {
    constructor(
        createContext: (CefRequestContextHandler) -> CefRequestContext,
        dispatchers: DispatcherProvider,
        urlResolver: BrowserUrlResolver,
    ) : this(dispatchers, { DesktopBrowserTransfer(createContext, it, urlResolver).capture() })

    private val monitor = Any()
    private val operations = mutableSetOf<Job>()
    private var isClosed = false

    suspend fun capture(address: String): JsonObject = coroutineScope {
        val url = requireNotNull(normalizeBrowserUrl(address)) { "Invalid source URL" }
        val operation = currentCoroutineContext().job
        synchronized(monitor) {
            check(!isClosed) { "Browser is closed" }
            operations.add(operation)
        }
        try {
            withTimeout(REQUEST_TIMEOUT) {
                withContext(dispatchers.io) { transfer(url) }
            }
        } finally {
            synchronized(monitor) { operations.remove(operation) }
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
