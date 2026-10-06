package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.impl.domain.desktopBrowserFailure
import kotlinx.coroutines.CancellationException
import org.cef.CefApp
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
 * Windowed Swing embedding works on macOS and Windows (including ARM64 without JOGL).
 * Each instance creates its own client and request context with empty native cache settings.
 * JCEF disposes that context in CefBrowser.onBeforeClose; clients close after their browser closes.
 */
internal class DesktopBrowserEngine(
    app: CefApp,
    private val isReleased: () -> Boolean,
    private val changed: (BrowserViewState) -> Unit,
    private val onClosed: () -> Unit = {},
) : DesktopBrowserRuntime {
    private val log = Log.tag("DesktopBrowserEngine")
    private val isClosed = AtomicBoolean(false)
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
        checkNotNull(
            CefRequestContext.createContext(policy.contextHandler),
        ) { "Browser request context is unavailable" }
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
    private val browser: CefBrowser
    private var isReady = false

    init {
        var isCreated = false
        try {
            check(!context.isGlobal) { "Browser request context must be isolated" }
            client.addRequestHandler(policy.requestHandler)
            client.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
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
                    // CefClient invokes browser.onBeforeClose (which disposes the context), then removes
                    // this browser from its registry and finally releases the marked client.
                    isClosed.set(true)
                    client.dispose()
                    onClosed()
                }

                override fun onAfterCreated(browser: CefBrowser) {
                    isNativeCreated.set(true)
                    if (isClosed.get() || isReleased()) {
                        closeCreatedBrowser(browser)
                        return
                    }
                    // This callback runs on CEF UI, as required by setPreference; no page has loaded yet.
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
                            if (commands.isEmpty()) changed(BrowserViewState()) else commands.forEach(::execute)
                        } else {
                            log.e(IllegalStateException("JCEF permission preferences rejected")) {
                                "Desktop browser initialization failed"
                            }
                            changed(BrowserViewState(error = BrowserViewError.EngineUnavailable))
                            release()
                        }
                    }
                }
            })
            client.addDisplayHandler(object : CefDisplayHandlerAdapter() {
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
            })
            client.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadingStateChange(
                    browser: CefBrowser,
                    isLoading: Boolean,
                    canGoBack: Boolean,
                    canGoForward: Boolean,
                ) {
                    if (!isBootstrap.get()) onSwing { navigation?.loading(isLoading, canGoBack, canGoForward) }
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
            })
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
        if (!isClosed.get() && isCreationRequested.compareAndSet(false, true)) browser.createImmediately()
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
        if (!isClosed.compareAndSet(false, true)) return
        pending.clear()
        if (!isCreationRequested.get()) {
            // The component has never escaped and creation has never been queued: no native callback can race.
            browser.onBeforeClose()
            client.dispose()
            onClosed()
        } else if (isNativeCreated.get()) {
            closeCreatedBrowser(browser)
        }
        // A pending create owns client/context until onAfterCreated; closing earlier loses the native close.
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
        if (SwingUtilities.isEventDispatchThread()) guarded() else SwingUtilities.invokeLater(guarded)
    }

    private companion object {
        const val CONTENT_SETTING_BLOCK = 2
    }
}
