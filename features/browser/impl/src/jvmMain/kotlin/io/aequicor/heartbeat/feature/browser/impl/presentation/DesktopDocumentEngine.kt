package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.browser.impl.domain.OpenBrowserDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement
import org.cef.CefApp
import org.cef.CefRequestContextSettings
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefRequestContext
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import java.awt.Component
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * One native browser showing [document] at its own origin: its own client and an in-memory request context gated by
 * [DesktopDocumentPolicy], the message router only when the document has a bridge (calls run in [callsScope]). The
 * browser starts on its own blank page; once CEF reports it created, permissions and network prediction are switched
 * off in its context and the document loads. Data changes reach a loaded page through [deliver]. [unavailable] reports
 * a failed creation, configuration, load or renderer. Mirrors the close protocol of [DesktopBrowserEngine].
 */
internal class DesktopDocumentEngine(
    app: CefApp,
    private val document: OpenBrowserDocument,
    callsScope: CoroutineScope,
    private val isReleased: () -> Boolean,
    private val unavailable: () -> Unit,
) {
    private val log = Log.tag("DesktopDocumentEngine")
    private val url = desktopDocumentUrl(document.id)
    private val isClosed = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()
    private val isCreationRequested = AtomicBoolean(false)
    private val isNativeCreated = AtomicBoolean(false)
    private val isCloseIssued = AtomicBoolean(false)
    private val isBootstrap = AtomicBoolean(true)
    private val policy = DesktopDocumentPolicy(
        url = url,
        page = ::page,
        isReleased = { isClosed.get() || isReleased() },
        isBootstrap = isBootstrap::get,
        unavailable = { onSwing(::fail) },
    )
    private val client = app.createClient()
    private val context = try {
        checkNotNull(
            CefRequestContext.createContext(
                CefRequestContextSettings().apply {
                    cache_path = ""
                    persist_session_cookies = false
                },
                policy.contextHandler,
            ),
        ) { "Document request context is unavailable" }
    } catch (error: CancellationException) {
        dispose()
        throw error
    } catch (error: Exception) {
        dispose()
        throw error
    } catch (error: LinkageError) {
        dispose()
        throw error
    }
    private val browser: CefBrowser

    /** Added to the client as soon as it exists, so disposing the client always disposes the native router. */
    private var router: DesktopDocumentRouter? = null

    /** Data the shown page holds: what it was served with, then what [deliver] sent; written on EDT and CEF IO. */
    @Volatile
    private var delivered: Delivered? = null

    @Volatile
    private var isLoaded = false

    init {
        var isCreated = false
        try {
            check(!context.isGlobal) { "Document request context must be isolated" }
            router = document.document.bridge?.let { bridge ->
                DesktopDocumentRouter(url, DesktopDocumentCalls(bridge, callsScope)).also {
                    client.addMessageRouter(it.router)
                }
            }
            client.addRequestHandler(policy.requestHandler)
            client.addLifeSpanHandler(LifeSpan())
            client.addLoadHandler(Load())
            client.addDisplayHandler(
                object : CefDisplayHandlerAdapter() {
                    override fun onConsoleMessage(
                        browser: CefBrowser,
                        level: CefSettings.LogSeverity,
                        message: String,
                        source: String,
                        line: Int,
                    ): Boolean = true
                },
            )
            installDesktopBrowserRestrictions(client)
            browser = client.createBrowser(BLANK_PAGE, false, false, context)
            isCreated = true
        } finally {
            if (!isCreated) {
                context.dispose()
                dispose()
            }
        }
    }

    /** The native view; first access on EDT queues native creation. */
    val component: Component
        get() {
            check(SwingUtilities.isEventDispatchThread())
            if (!isClosed.get() && isCreationRequested.compareAndSet(false, true)) browser.createImmediately()
            return browser.uiComponent
        }

    /** Sends [data] to the loaded page unless it already holds it; a page still loading reads it when served. */
    fun deliver(data: JsonElement?) {
        check(SwingUtilities.isEventDispatchThread())
        if (isClosed.get() || !isLoaded || delivered?.data == data) return
        delivered = Delivered(data)
        browser.mainFrame?.executeJavaScript(desktopDocumentDataScript(data), url, 0)
    }

    /** Closes the native browser on EDT; native callbacks finish the closure for [awaitClosed]. */
    fun release() {
        check(SwingUtilities.isEventDispatchThread())
        if (!isClosed.compareAndSet(false, true)) return
        if (!isCreationRequested.get()) {
            // Creation was never queued, so no native callback can race.
            browser.onBeforeClose()
            dispose()
        } else if (isNativeCreated.get()) {
            closeCreatedBrowser(browser)
        }
        // A pending creation owns the client and context until onAfterCreated closes them.
    }

    suspend fun awaitClosed() = closed.await()

    /** The page with the data of this moment: the API comes first, so the page's own scripts already read it. */
    private fun page(): ByteArray {
        val data = document.data.value
        delivered = Delivered(data)
        val query = router?.let { desktopDocumentQueryFunction }
        return desktopDocumentBody(desktopDocumentHtml(document.document.html, data, query))
    }

    private fun fail() {
        if (isReleased()) return
        unavailable()
    }

    private fun dispose() {
        router?.close()
        client.dispose()
        closed.complete(Unit)
    }

    private fun closeCreatedBrowser(browser: CefBrowser) {
        if (isCloseIssued.compareAndSet(false, true)) browser.close(true)
    }

    private fun onSwing(action: () -> Unit) {
        val guarded = {
            if (!isClosed.get() && !isReleased()) {
                try {
                    action()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.e(error.redactedFailure()) { "Desktop document native operation failed" }
                    unavailable()
                }
            }
        }
        if (SwingUtilities.isEventDispatchThread()) guarded() else SwingUtilities.invokeLater(guarded)
    }

    private inner class LifeSpan : CefLifeSpanHandlerAdapter() {
        override fun onBeforePopup(
            browser: CefBrowser,
            frame: CefFrame,
            targetUrl: String,
            targetFrameName: String,
        ): Boolean = true

        override fun onAfterCreated(browser: CefBrowser) {
            isNativeCreated.set(true)
            if (isClosed.get() || isReleased()) {
                // Closing reentrantly here can run onBeforeClose before CEF has finished creation.
                SwingUtilities.invokeLater { closeCreatedBrowser(browser) }
                return
            }
            // CEF UI thread, as setPreference requires; only the blank page has loaded so far.
            val rejected = DOCUMENT_PREFERENCES.filter { (name, value) -> context.setPreference(name, value) != null }
            if (rejected.isEmpty()) {
                isBootstrap.set(false)
                browser.loadURL(url)
            } else {
                log.e(IllegalStateException("JCEF document preferences rejected: ${rejected.map { it.first }}")) {
                    "Desktop document initialization failed"
                }
                onSwing(::fail)
            }
        }

        override fun onBeforeClose(browser: CefBrowser) {
            isClosed.set(true)
        }

        override fun onAfterClose(browser: CefBrowser) {
            // Follows JCEF's wrapper disposal and browser registry removal, like DesktopBrowserEngine.
            dispose()
        }

        override fun onCreationFailed(browser: CefBrowser) {
            // JCEF has already disposed the unregistered browser wrapper on this terminal path.
            isClosed.set(true)
            dispose()
            log.e(IllegalStateException("JCEF document browser creation failed")) {
                "Desktop document initialization failed"
            }
            SwingUtilities.invokeLater(::fail)
        }
    }

    private inner class Load : CefLoadHandlerAdapter() {
        override fun onLoadStart(browser: CefBrowser, frame: CefFrame, transitionType: CefRequest.TransitionType) {
            if (frame.isMain) isLoaded = false
        }

        override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
            if (frame.isMain && isDesktopDocumentFrame(url, frame.url)) {
                isLoaded = true
                // Data that changed while the page loaded was not served with it.
                onSwing { deliver(document.data.value) }
            }
        }

        override fun onLoadError(
            browser: CefBrowser,
            frame: CefFrame,
            errorCode: CefLoadHandler.ErrorCode,
            errorText: String,
            failedUrl: String,
        ) {
            if (frame.isMain && !isBootstrap.get() && errorCode != CefLoadHandler.ErrorCode.ERR_ABORTED) {
                log.w(IllegalStateException("JCEF document load error code=$errorCode")) { "Document load failed" }
                onSwing(::fail)
            }
        }
    }

    /** Data a page holds, distinguishing a page without data from one that was never served. */
    private data class Delivered(val data: JsonElement?)

    private companion object {
        const val BLANK_PAGE = "about:blank"
        const val CONTENT_SETTING_BLOCK = 2
        const val NETWORK_PREDICTION_NEVER = 2

        /** Device permissions are refused, and no DNS prefetch or preconnect runs for hints in the page. */
        val DOCUMENT_PREFERENCES = listOf(
            "profile.default_content_setting_values.media_stream_mic" to CONTENT_SETTING_BLOCK,
            "profile.default_content_setting_values.media_stream_camera" to CONTENT_SETTING_BLOCK,
            "profile.default_content_setting_values.geolocation" to CONTENT_SETTING_BLOCK,
            "profile.default_content_setting_values.notifications" to CONTENT_SETTING_BLOCK,
            "net.network_prediction_options" to NETWORK_PREDICTION_NEVER,
        )
    }
}
