package io.aequicor.heartbeat.feature.browser.impl.ui

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserViewCommand
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserViewState
import io.aequicor.heartbeat.feature.browser.impl.presentation.CocoaDocumentClipAccess
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopBrowserDevToolsCalls
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopBrowserRuntime
import io.aequicor.heartbeat.feature.browser.impl.presentation.MacDocumentClipAccess
import io.aequicor.heartbeat.feature.browser.impl.presentation.MacDocumentNativeClipping
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import org.cef.CefApp
import org.cef.OS
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefRequestContext
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestContextHandlerAdapter
import java.awt.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** A directly owned test browser exposes JCEF's public window geometry without production test hooks. */
internal class NativeViewportBrowserRuntime(
    app: CefApp,
    private val changed: (BrowserViewState) -> Unit,
    private val clippingVerified: CompletableFuture<Unit>,
) : DesktopBrowserRuntime {
    private val client = app.createClient()
    private val context = checkNotNull(CefRequestContext.createContext(object : CefRequestContextHandlerAdapter() {}))
    private val closed = CompletableDeferred<Unit>()
    private val isReleased = AtomicBoolean()
    private val isCreated = AtomicBoolean()
    private val isRequested = AtomicBoolean()
    private val calls = DesktopBrowserDevToolsCalls(Log.tag("NativeViewportBrowserRuntime"))
    private var initialUrl = ""
    private var clipping: MacDocumentNativeClipping? = null
    val browser: CefBrowser

    init {
        client.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onAfterCreated(browser: CefBrowser) {
                isCreated.set(true)
                SwingUtilities.invokeLater {
                    if (isReleased.get()) {
                        browser.close(true)
                    } else {
                        installClipping()
                        browser.loadURL(initialUrl)
                    }
                }
            }

            override fun onAfterClose(browser: CefBrowser) {
                calls.failAll()
                context.dispose()
                client.dispose()
                closed.complete(Unit)
            }
        })
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain && frame.url != "about:blank") {
                    SwingUtilities.invokeLater {
                        clipping?.documentLoaded()
                        changed(BrowserViewState(title = "compose-native-ready", isLoading = false))
                    }
                }
            }
        })
        browser = client.createBrowser("about:blank", false, false, context)
    }

    override val component: Component
        get() {
            if (isRequested.compareAndSet(false, true)) browser.createImmediately()
            return browser.uiComponent
        }

    override fun start(initialUrl: String) {
        this.initialUrl = initialUrl
        if (isCreated.get()) browser.loadURL(initialUrl)
    }

    override fun execute(command: BrowserViewCommand) = error("The viewport probe does not issue browser commands")

    override suspend fun devTools(method: String, arguments: JsonObject): String = calls.call(
        post = { SwingUtilities.invokeLater(it) },
        isClosed = isReleased::get,
    ) { browser.devToolsClient?.executeDevToolsMethod(method, arguments.toString()) }

    override fun release() {
        clipping?.close()
        clipping = null
        if (isReleased.compareAndSet(false, true) && isCreated.get()) browser.close(true)
    }

    override suspend fun awaitClosed() = closed.await()

    private fun installClipping() {
        if (!OS.isMacintosh()) {
            clippingVerified.complete(Unit)
            return
        }
        val nativeAccess = CocoaDocumentClipAccess()
        clipping = MacDocumentNativeClipping(
            browser.uiComponent as JComponent,
            onFailure = {
                clippingVerified.completeExceptionally(AssertionError("Native clipping initialization failed"))
            },
            access = MacDocumentClipAccess { component ->
                nativeAccess.apply(component).also { count ->
                    if (count > 0) clippingVerified.complete(Unit)
                }
            },
        ).also { it.request() }
    }
}
