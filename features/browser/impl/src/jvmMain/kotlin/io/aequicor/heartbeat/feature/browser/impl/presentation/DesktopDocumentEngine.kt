package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentSizing
import io.aequicor.heartbeat.feature.browser.impl.domain.OpenBrowserDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement
import org.cef.CefApp
import org.cef.CefRequestContextSettings
import org.cef.CefSettings
import org.cef.OS
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
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * One native browser showing [document] at its own origin: its own client and an in-memory request context gated by
 * [DesktopDocumentPolicy], the message router only when the document has a bridge (calls run in [callsScope] on
 * [callsDispatcher]). The
 * browser starts on its own blank page; once CEF reports it created, permissions and network prediction are switched
 * off in its context and the document loads. Data changes reach a loaded page through [deliver]. [unavailable] reports
 * a failed creation, configuration, load or renderer. Mirrors the close protocol of [DesktopBrowserEngine].
 */
internal class DesktopDocumentEngine(
    app: CefApp,
    private val document: OpenBrowserDocument,
    callsScope: CoroutineScope,
    callsDispatcher: CoroutineDispatcher,
    private val isReleased: () -> Boolean,
    private val unavailable: () -> Unit,
    private val presentation: DesktopDocumentPresentation = DesktopDocumentPresentation(),
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
    private var nativeClipping: MacDocumentNativeClipping? = null
    private var nativeRounding: MacDocumentNativeRounding? = null

    /** Added to the client as soon as it exists, so disposing the client always disposes the native router. */
    private var router: DesktopDocumentRouter? = null
    private var layoutRouter: DesktopDocumentLayoutRouter? = null

    /** Data the latest page response carried, written on CEF IO when the document is served. */
    @Volatile
    private var served: Delivered? = null

    /** Data the shown page holds: what it was served with, then what [deliver] sent; EDT only. */
    private var delivered: Delivered? = null

    @Volatile
    private var isLoaded = false

    init {
        var isCreated = false
        try {
            check(!context.isGlobal) { "Document request context must be isolated" }
            router = document.document.bridge?.let { bridge ->
                DesktopDocumentRouter(url, DesktopDocumentCalls(bridge, callsScope, callsDispatcher)).also {
                    client.addMessageRouter(it.router)
                }
            }
            // Every retained page can later be mounted with Content presentation without being reloaded.
            layoutRouter = DesktopDocumentLayoutRouter(url, presentation.measurements).also {
                client.addMessageRouter(it.router)
            }
            client.addRequestHandler(policy.requestHandler)
            client.addLifeSpanHandler(LifeSpan())
            client.addLoadHandler(Load())
            client.addKeyboardHandler(DesktopDocumentKeyboard(presentation.dismissal))
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

    /** Rearms native attachment clipping after a paint without doing AppKit work inside Swing painting. */
    fun nativePainted() {
        check(SwingUtilities.isEventDispatchThread())
        if (!isClosed.get() && !isReleased()) nativeClipping?.nativePainted()
    }

    /** Restores the previous modal mask synchronously before the retained page moves to a different host. */
    fun updateCornerRadius(radius: Double) {
        check(SwingUtilities.isEventDispatchThread())
        if (!OS.isMacintosh() || isClosed.get() || isReleased()) return
        val rounding = nativeRounding ?: if (radius > 0) {
            MacDocumentNativeRounding().also { nativeRounding = it }
        } else {
            null
        }
        rounding?.update(radius)
        if (radius > 0 && isNativeCreated.get()) nativeClipping?.request()
    }

    /** Sends [data] to the loaded page unless it already holds it; a page still loading reads it when served. */
    fun deliver(data: JsonElement?) {
        check(SwingUtilities.isEventDispatchThread())
        if (isClosed.get() || !isLoaded) return
        val next = Delivered(data, presentation.theme.value)
        if (delivered?.data != next.data) {
            browser.mainFrame?.executeJavaScript(desktopDocumentDataScript(next.data), url, 0)
        }
        if (delivered?.theme != next.theme) {
            next.theme?.let { browser.mainFrame?.executeJavaScript(desktopDocumentThemeScript(it), url, 0) }
        }
        delivered = next
    }

    /** Closes the native browser on EDT; native callbacks finish the closure for [awaitClosed]. */
    fun release() {
        check(SwingUtilities.isEventDispatchThread())
        closeNativeClipping()
        if (!isClosed.compareAndSet(false, true)) return
        presentation.measurements.close()
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
        val theme = presentation.theme.value
        served = Delivered(data, theme)
        val query = router?.let { desktopDocumentQueryFunction }
        val layout = layoutRouter?.let {
            DesktopDocumentLayoutScript(
                desktopDocumentLayoutFunction,
                presentation.measurements.beginPage(),
                hasContentStyles = document.document.sizing == BrowserDocumentSizing.Content,
            )
        }
        return desktopDocumentBody(desktopDocumentHtml(document.document.html, data, query, theme, layout))
    }

    private fun fail() {
        if (isReleased()) return
        unavailable()
    }

    /** Starts only after CEF creation; extraction or a hidden initial layout cannot exhaust attachment retries. */
    private fun requestNativeClipping(isDocumentLoaded: Boolean) {
        if (!OS.isMacintosh()) return
        try {
            val clipping = nativeClipping ?: MacDocumentNativeClipping(
                browser.uiComponent as JComponent,
                ::fail,
                onClipped = { nativeRounding?.apply(browser.uiComponent as JComponent) },
            ).also {
                nativeClipping = it
            }
            if (isDocumentLoaded) clipping.documentLoaded() else clipping.request()
        } catch (error: LinkageError) {
            // Native linkage can fail while constructing the access object, before its guarded attempts exist.
            log.e(error.redactedFailure()) { "Native document clipping is unavailable" }
            fail()
        }
    }

    private fun closeNativeClipping() {
        nativeClipping?.close()
        nativeClipping = null
        nativeRounding?.close()
        nativeRounding = null
    }

    private fun dispose() {
        router?.close()
        layoutRouter?.close()
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
                onSwing { requestNativeClipping(isDocumentLoaded = false) }
            } else {
                log.e(IllegalStateException("JCEF document preferences rejected: ${rejected.map { it.first }}")) {
                    "Desktop document initialization failed"
                }
                onSwing(::fail)
            }
        }

        override fun onBeforeClose(browser: CefBrowser) {
            isClosed.set(true)
            SwingUtilities.invokeLater(::closeNativeClipping)
        }

        override fun onAfterParentChanged(browser: CefBrowser) {
            onSwing {
                // Native reparenting completes asynchronously after Swing addNotify/paint. Refresh the current
                // visible host only after that acknowledgement; the earlier paint may have targeted the old window.
                val view = browser.uiComponent as JComponent
                if (view.isShowing) {
                    view.revalidate()
                    view.repaint()
                    requestNativeClipping(isDocumentLoaded = isLoaded)
                }
            }
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
                onSwing {
                    // The new page holds what it was served with; data that changed since then is sent now.
                    delivered = served
                    isLoaded = true
                    requestNativeClipping(isDocumentLoaded = true)
                    deliver(document.data.value)
                }
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
    private data class Delivered(val data: JsonElement?, val theme: DesktopDocumentTheme?)

    private companion object {
        const val BLANK_PAGE = "about:blank"
        const val CONTENT_SETTING_BLOCK = 2
        const val NETWORK_PREDICTION_NEVER = 2

        /** A local port nothing listens on: connections through it fail at once and never leave the machine. */
        const val CLOSED_PROXY = "127.0.0.1:1"

        /**
         * Device permissions are refused, and no DNS prefetch or preconnect runs for hints in the page. WebRTC is out
         * of reach of the content security policy and request handlers: it may use UDP only through a proxy, and the
         * context's only proxy is a closed local port, also for loopback addresses, so neither STUN nor TURN over UDP
         * or TCP reaches a peer, local or remote, and peer host names are not resolved here. The document itself is
         * answered by its resource handler, never by the network, so the proxy does not affect it. Dictionary
         * preferences need the hb3 runtime.
         */
        val DOCUMENT_PREFERENCES: List<Pair<String, Any>> = listOf(
            "profile.default_content_setting_values.media_stream_mic" to CONTENT_SETTING_BLOCK,
            "profile.default_content_setting_values.media_stream_camera" to CONTENT_SETTING_BLOCK,
            "profile.default_content_setting_values.geolocation" to CONTENT_SETTING_BLOCK,
            "profile.default_content_setting_values.notifications" to CONTENT_SETTING_BLOCK,
            "net.network_prediction_options" to NETWORK_PREDICTION_NEVER,
            "webrtc.ip_handling_policy" to "disable_non_proxied_udp",
            // "<-loopback>" drops Chromium's implicit bypass, so local addresses go to the closed proxy as well.
            "proxy" to mapOf("mode" to "fixed_servers", "server" to CLOSED_PROXY, "bypass_list" to "<-loopback>"),
        )
    }
}
