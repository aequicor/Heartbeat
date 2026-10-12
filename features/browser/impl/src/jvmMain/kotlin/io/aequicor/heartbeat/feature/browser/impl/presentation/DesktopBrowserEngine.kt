package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserUrlResolver
import io.aequicor.heartbeat.feature.browser.impl.domain.desktopBrowserFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import org.cef.CefApp
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefRequestContext
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestContextHandler
import org.cef.network.CefRequest
import java.awt.Component
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Windowed Swing embedding works on macOS and Windows (including ARM64 without JOGL). Each instance
 * creates its own client and separately owned wrapper sharing the profile's persistent context.
 * Patched JCEF confirms wrapper disposal and registry cleanup before the client is released.
 */
internal class DesktopBrowserEngine(
    app: CefApp,
    dispatchers: DispatcherProvider,
    urlResolver: BrowserUrlResolver,
    private val isReleased: () -> Boolean,
    private val changed: (BrowserViewState) -> Unit,
    private val onClosed: () -> Unit = {},
    createContext: (CefRequestContextHandler) -> CefRequestContext,
) : DesktopBrowserRuntime {
    private val log = Log.tag("DesktopBrowserEngine")
    private val isClosed = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()
    private val isCreationRequested = AtomicBoolean(false)
    private val isNativeCreated = AtomicBoolean(false)
    private val isCloseIssued = AtomicBoolean(false)
    private val isBootstrap = AtomicBoolean(true)
    private val client = app.createClient()
    private var navigation: DesktopBrowserNavigation? = null
    private val pending = mutableListOf<BrowserViewCommand>()
    private val policy = DesktopBrowserPolicy(
        isReleased = { isClosed.get() || isReleased() },
        isBootstrap = isBootstrap::get,
        unsupported = { onSwing { navigation?.unsupported() } },
        open = { execute(BrowserViewCommand.Load(it)) },
        unavailable = { onSwing { changed(BrowserViewState(error = BrowserViewError.EngineUnavailable)) } },
    )
    private val context = try {
        createContext(policy.contextHandler)
    } catch (error: CancellationException) {
        client.dispose()
        throw error
    } catch (error: Exception) {
        client.dispose()
        throw error
    } catch (error: LinkageError) {
        client.dispose()
        throw error
    }
    private val downloads = DesktopBrowserDownload(createContext, dispatchers, urlResolver)
    private val browser: CefBrowser
    private var isReady = false

    override suspend fun captureResource(url: String): JsonObject = downloads.capture(url)

    override suspend fun devTools(method: String, arguments: JsonObject): String =
        suspendCancellableCoroutine { continuation ->
            onSwing {
                if (!continuation.isActive) return@onSwing
                val devTools = browser.devToolsClient
                if (devTools == null) {
                    continuation.resumeWithException(IllegalStateException("Browser is not ready"))
                } else {
                    val result = devTools.executeDevToolsMethod(method, arguments.toString())
                    continuation.invokeOnCancellation { result.cancel(false) }
                    result.whenComplete { value, error ->
                        if (error != null) {
                            continuation.resumeWithException(error)
                        } else {
                            continuation.resume(value)
                        }
                    }
                }
            }
        }

    init {
        var isCreated = false
        try {
            check(!context.isGlobal) { "Browser request context must be isolated" }
            client.addRequestHandler(policy.requestHandler)
            client.addLifeSpanHandler(
                object : CefLifeSpanHandlerAdapter() {
                    override fun onBeforePopup(
                        browser: CefBrowser,
                        frame: CefFrame,
                        targetUrl: String,
                        targetFrameName: String,
                    ): Boolean {
                        policy.popup(targetUrl)
                        return true
                    }

                    override fun onBeforeClose(browser: CefBrowser) {
                        isClosed.set(true)
                        downloads.cancel()
                    }

                    override fun onAfterClose(browser: CefBrowser) {
                        // This hook follows JCEF's wrapper disposal and browser registry removal.
                        // A queued Swing callback alone cannot establish that barrier on AppKit.
                        client.dispose()
                        completeClose()
                    }

                    override fun onCreationFailed(browser: CefBrowser) {
                        creationFailed()
                    }

                    override fun onAfterCreated(browser: CefBrowser) {
                        isNativeCreated.set(true)
                        if (isClosed.get() || isReleased()) {
                            // Closing reentrantly here can run onBeforeClose before CEF has
                            // finished creation. Queue the close after this native callback.
                            SwingUtilities.invokeLater { closeCreatedBrowser(browser) }
                            return
                        }
                        // This callback runs on CEF UI, as required by setPreference; no page has
                        // loaded yet.
                        val preferences = listOf(
                            "profile.default_content_setting_values.media_stream_mic",
                            "profile.default_content_setting_values.media_stream_camera",
                            "profile.default_content_setting_values.geolocation",
                            "profile.default_content_setting_values.notifications",
                        )
                        val isDenied = preferences.all { context.setPreference(it, CONTENT_SETTING_BLOCK) == null }
                        onSwing {
                            if (isDenied) {
                                isReady = true
                                val commands = pending.toList().also { pending.clear() }
                                if (commands.isEmpty()) {
                                    changed(BrowserViewState())
                                } else {
                                    commands.forEach(::execute)
                                }
                            } else {
                                log.e(
                                    IllegalStateException("JCEF permission preferences rejected"),
                                ) {
                                    "Desktop browser initialization failed"
                                }
                                changed(
                                    BrowserViewState(error = BrowserViewError.EngineUnavailable),
                                )
                                release()
                            }
                        }
                    }
                },
            )
            client.addDisplayHandler(
                object : CefDisplayHandlerAdapter() {
                    override fun onAddressChange(browser: CefBrowser, frame: CefFrame, url: String) {
                        if (frame.isMain) onSwing { navigation?.address(url) }
                    }

                    override fun onTitleChange(browser: CefBrowser, title: String) {
                        onSwing { navigation?.title(title) }
                    }

                    override fun onConsoleMessage(
                        browser: CefBrowser,
                        level: CefSettings.LogSeverity,
                        message: String,
                        source: String,
                        line: Int,
                    ): Boolean = true
                },
            )
            client.addLoadHandler(
                object : CefLoadHandlerAdapter() {
                    override fun onLoadingStateChange(
                        browser: CefBrowser,
                        isLoading: Boolean,
                        canGoBack: Boolean,
                        canGoForward: Boolean,
                    ) {
                        if (!isBootstrap.get()) {
                            onSwing { navigation?.loading(isLoading, canGoBack, canGoForward) }
                        }
                    }

                    override fun onLoadStart(
                        browser: CefBrowser,
                        frame: CefFrame,
                        transitionType: CefRequest.TransitionType,
                    ) {
                        if (frame.isMain && !isBootstrap.get()) onSwing { navigation?.started() }
                    }

                    override fun onLoadError(
                        browser: CefBrowser,
                        frame: CefFrame,
                        errorCode: CefLoadHandler.ErrorCode,
                        errorText: String,
                        failedUrl: String,
                    ) {
                        if (frame.isMain && errorCode != CefLoadHandler.ErrorCode.ERR_ABORTED) {
                            onSwing {
                                log.w(IllegalStateException("JCEF load error code=$errorCode")) {
                                    "Main document load failed"
                                }
                                navigation?.failed()
                            }
                        }
                    }
                },
            )
            installDesktopBrowserRestrictions(client)
            browser = client.createBrowser("about:blank", false, false, context)
            navigation = DesktopBrowserNavigation(browser, changed)
            isCreated = true
        } finally {
            if (!isCreated) {
                context.dispose()
                client.dispose()
            }
        }
    }

    override val component: Component
        get() {
            requestCreation()
            return browser.uiComponent
        }

    override fun start(initialUrl: String) {
        if (initialUrl.isNotEmpty()) execute(BrowserViewCommand.Load(initialUrl))
        requestCreation()
    }

    private fun requestCreation() {
        check(SwingUtilities.isEventDispatchThread())
        if (!isClosed.get() && isCreationRequested.compareAndSet(false, true)) {
            browser.createImmediately()
        }
    }

    override fun execute(command: BrowserViewCommand) {
        onSwing {
            if (!isReady) {
                if (command == BrowserViewCommand.Stop) pending.clear() else pending += command
            } else {
                if (command is BrowserViewCommand.Load) isBootstrap.set(false)
                navigation?.execute(command)
            }
        }
    }

    override fun release() {
        check(SwingUtilities.isEventDispatchThread())
        downloads.cancel()
        if (!isClosed.compareAndSet(false, true)) return
        pending.clear()
        if (!isCreationRequested.get()) {
            // The component has never escaped and creation has never been queued: no native
            // callback can race.
            browser.onBeforeClose()
            client.dispose()
            completeClose()
        } else if (isNativeCreated.get()) {
            closeCreatedBrowser(browser)
        }
        // A pending create owns client/context until onAfterCreated; closing earlier loses the
        // native close.
    }

    override suspend fun awaitClosed() {
        downloads.awaitClosed()
        closed.await()
    }

    private fun creationFailed() {
        // JCEF has already disposed the unregistered browser wrapper on this terminal path.
        isClosed.set(true)
        downloads.cancel()
        client.dispose()
        completeClose()
        log.e(IllegalStateException("JCEF browser creation failed")) { "Desktop browser initialization failed" }
        SwingUtilities.invokeLater {
            if (!isReleased()) changed(BrowserViewState(error = BrowserViewError.EngineUnavailable))
        }
    }

    private fun completeClose() {
        if (closed.complete(Unit)) onClosed()
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
                    log.e(error.desktopBrowserFailure()) { "Desktop browser native operation failed" }
                    changed(BrowserViewState(error = BrowserViewError.EngineUnavailable))
                    release()
                }
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            guarded()
        } else {
            SwingUtilities.invokeLater(guarded)
        }
    }

    private companion object {
        const val CONTENT_SETTING_BLOCK = 2
    }
}
