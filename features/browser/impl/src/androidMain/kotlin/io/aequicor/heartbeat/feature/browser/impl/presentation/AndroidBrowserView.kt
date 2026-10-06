package io.aequicor.heartbeat.feature.browser.impl.presentation

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.api.isBrowserUrlAllowed
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRequestKind
import io.aequicor.heartbeat.feature.browser.impl.domain.isBrowserRequestAllowed

/**
 * A surface-owned browser without persisted authorization: cookies and DOM storage are disabled
 * because Android's default WebView storage is shared across profiles in the application process.
 * Popup windows, native permissions, file uploads and non-HTTP(S) main navigation are denied.
 * Child documents may use browser-managed memory URLs under WebView's origin and sandbox rules.
 * Native history lasts only as long as this view; URLs and titles are never logged.
 */
internal class AndroidBrowserView(context: Context, private val surface: BrowserSurface, onRecreate: () -> Unit) :
    WebView(context),
    BrowserViewController {
    private val log = Log.tag("AndroidBrowserView")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderer = BrowserRendererLifecycle(
        onFailure = {
            surface.changed(
                this,
                BrowserViewState(url = address, title = pageTitle, error = BrowserViewError.EngineUnavailable),
            )
        },
        onDestroy = ::destroyRenderer,
        onDetach = { surface.detached(this) },
        onRecreate = onRecreate,
    )
    private val isReleased: Boolean get() = !renderer.isActive
    private var hasReceivedCommand = false
    private var isStopped = false
    private var isLoading = false
    private var address = ""
    private var pageTitle = ""
    private var failure: BrowserViewError? = null

    init {
        settings.apply {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = true
            setGeolocationEnabled(false)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(false)
            setAcceptThirdPartyCookies(this@AndroidBrowserView, false)
        }
        webViewClient = NavigationClient()
        webChromeClient = ChromeClient()
        surface.attached(this)
        if (!hasReceivedCommand && surface.initialUrl.isNotEmpty()) {
            execute(BrowserViewCommand.Load(surface.initialUrl))
        } else {
            publish()
        }
    }

    override fun execute(command: BrowserViewCommand) {
        onMain {
            if (!renderer.isActive) {
                renderer.retry(command)
                return@onMain
            }
            hasReceivedCommand = true
            when (command) {
                is BrowserViewCommand.Load -> loadAddress(command.url)

                BrowserViewCommand.Back -> if (canGoBack()) beginNavigation { goBack() }

                BrowserViewCommand.Forward -> if (canGoForward()) beginNavigation { goForward() }

                BrowserViewCommand.Reload -> if (address.isNotEmpty()) beginNavigation { reload() }

                BrowserViewCommand.Stop -> {
                    isStopped = true
                    isLoading = false
                    stopLoading()
                    publish()
                }
            }
        }
    }

    /** Invalidates callbacks before stopping native work and destroying the view on the UI thread. */
    fun release() = onMain { renderer.release() }

    private fun destroyRenderer(isRendererGone: Boolean) {
        mainHandler.removeCallbacksAndMessages(null)
        (parent as? ViewGroup)?.removeView(this)
        if (!isRendererGone) {
            stopLoading()
            webChromeClient = null
            webViewClient = WebViewClient()
            clearHistory()
            removeAllViews()
        }
        destroy()
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post { action() }
        }
    }

    private fun loadAddress(value: String) {
        if (isLoading && address == value) return
        if (!isBrowserUrlAllowed(value)) {
            failure = BrowserViewError.UnsupportedAddress
            publish()
            return
        }
        address = value
        pageTitle = ""
        beginNavigation { loadUrl(value) }
    }

    private fun beginNavigation(action: () -> Unit) {
        isStopped = false
        failure = null
        isLoading = true
        action()
        publish()
    }

    @HighFrequency
    private fun publish() {
        if (isReleased) return
        surface.changed(
            this,
            BrowserViewState(
                url = address,
                title = pageTitle,
                isLoading = isLoading,
                isBackAvailable = canGoBack(),
                isForwardAvailable = canGoForward(),
                error = failure,
            ),
        )
    }

    @HighFrequency
    private fun isActiveMainRequest(request: WebResourceRequest): Boolean {
        if (isReleased || isStopped) return false
        return request.isForMainFrame && request.url.toString() == address
    }

    private inner class NavigationClient : WebViewClient() {
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (renderer.isActive) {
                log.e(IllegalStateException("WebView renderer terminated: crashed=${detail.didCrash()}")) {
                    "Browser renderer unavailable"
                }
                renderer.rendererGone()
            }
            return true
        }

        @HighFrequency
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            log.v { "Checking native navigation" }
            if (isReleased) return true
            val kind = if (request.isForMainFrame) BrowserRequestKind.MainDocument else BrowserRequestKind.ChildDocument
            val isAllowed = isBrowserRequestAllowed(request.url.toString(), kind)
            if (request.isForMainFrame) {
                if (isAllowed) {
                    address = request.url.toString()
                    isStopped = false
                } else {
                    failure = BrowserViewError.UnsupportedAddress
                    isLoading = false
                    publish()
                }
            }
            return !isAllowed
        }

        @HighFrequency
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            log.v { "Main document started" }
            if (isReleased || isStopped) return
            val current = url.orEmpty()
            if (!isBrowserUrlAllowed(current)) {
                stopLoading()
                isLoading = false
                failure = BrowserViewError.UnsupportedAddress
                publish()
                return
            }
            address = current
            pageTitle = ""
            isLoading = true
            failure = null
            publish()
        }

        @HighFrequency
        override fun onPageFinished(view: WebView, url: String?) {
            log.v { "Main document finished" }
            if (isReleased || url != address) return
            isLoading = false
            pageTitle = view.title.orEmpty()
            publish()
        }

        @HighFrequency
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            log.v { "Native history changed" }
            if (isReleased) return
            if (url != null && isBrowserUrlAllowed(url)) address = url
            publish()
        }

        @HighFrequency
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            log.v { "Native resource load failed" }
            if (isActiveMainRequest(request)) {
                log.w(
                    IllegalStateException("WebView load error code=${error.errorCode}"),
                ) { "Main document load failed" }
                isLoading = false
                failure = BrowserViewError.LoadFailed
                publish()
            }
        }

        @HighFrequency
        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            log.v { "Native resource HTTP error" }
            if (isActiveMainRequest(request)) {
                log.w(IllegalStateException("WebView HTTP status=${errorResponse.statusCode}")) {
                    "Main document HTTP load failed"
                }
                isLoading = false
                failure = BrowserViewError.LoadFailed
                publish()
            }
        }

        @HighFrequency
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            log.v { "Rejecting invalid TLS certificate" }
            handler.cancel()
            if (!isReleased && !isStopped && error.url == address) {
                log.w(IllegalStateException("WebView TLS error code=${error.primaryError}")) {
                    "Main document TLS load failed"
                }
                isLoading = false
                failure = BrowserViewError.LoadFailed
                publish()
            }
        }
    }

    private inner class ChromeClient : WebChromeClient() {
        @HighFrequency
        override fun onReceivedTitle(view: WebView, title: String?) {
            log.v { "Main document title changed" }
            if (isReleased) return
            pageTitle = title.orEmpty()
            publish()
        }

        @HighFrequency
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            log.v { "Denying popup window" }
            return false
        }

        @HighFrequency
        override fun onPermissionRequest(request: PermissionRequest) {
            log.v { "Denying native permission request" }
            request.deny()
        }

        @HighFrequency
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            log.v { "Denying geolocation request" }
            callback.invoke(origin, false, false)
        }

        @HighFrequency
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: WebChromeClient.FileChooserParams,
        ): Boolean {
            log.v { "Denying file chooser" }
            filePathCallback.onReceiveValue(null)
            return true
        }
    }
}
