package io.aequicor.heartbeat.feature.browser.impl.ui

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserViewCommand
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserViewState
import io.aequicor.heartbeat.feature.browser.impl.presentation.CocoaDocumentClipAccess
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopBrowserContainer
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
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer

/** A directly owned test browser exposes JCEF's public window geometry without production test hooks. */
internal class NativeViewportBrowserRuntime(
    app: CefApp,
    private val changed: (BrowserViewState) -> Unit,
    private val clippingVerified: CompletableFuture<Unit>,
    private var isAttachmentHeld: Boolean = false,
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
    private val container = DesktopBrowserContainer(onNativePaint = {
        if (isPaintObservationEnabled) clipping?.nativePainted()
    })
    private var paintTimer: Timer? = null
    private var holdTimer: Timer? = null
    var isPaintObservationEnabled = !isAttachmentHeld
    val attachmentHeldPastLoadBurst = CompletableFuture<Unit>()
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
                        if (isAttachmentHeld) {
                            holdTimer = Timer(ATTACHMENT_HOLD_MILLIS) {
                                attachmentHeldPastLoadBurst.complete(Unit)
                            }.apply {
                                isRepeats = false
                                start()
                            }
                        }
                        changed(BrowserViewState(title = "compose-native-ready", isLoading = false))
                    }
                }
            }
        })
        browser = client.createBrowser("about:blank", false, false, context)
        container.add(browser.uiComponent)
    }

    override val component: Component
        get() {
            if (isRequested.compareAndSet(false, true)) {
                browser.createImmediately()
                if (isAttachmentHeld) paintTimer = Timer(PAINT_INTERVAL_MILLIS) { paintNative() }.apply { start() }
            }
            return container
        }

    /** Continuous real JCEF painting postpones its public delayed parent attachment until this barrier opens. */
    fun releaseAttachmentHold() {
        isAttachmentHeld = false
        paintTimer?.stop()
        paintTimer = null
    }

    fun paintNative() {
        check(SwingUtilities.isEventDispatchThread())
        if (!container.isShowing) return
        val graphics = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        try {
            graphics.clip = Rectangle(0, 0, container.width, container.height)
            container.paint(graphics)
        } finally {
            graphics.dispose()
        }
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
        releaseAttachmentHold()
        holdTimer?.stop()
        holdTimer = null
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

    private companion object {
        const val PAINT_INTERVAL_MILLIS = 25
        const val ATTACHMENT_HOLD_MILLIS = 750
    }
}
