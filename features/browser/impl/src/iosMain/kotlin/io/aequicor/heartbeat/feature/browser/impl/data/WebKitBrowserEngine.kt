package io.aequicor.heartbeat.feature.browser.impl.data

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.api.BrowserError
import io.aequicor.heartbeat.feature.browser.api.BrowserPage
import io.aequicor.heartbeat.feature.browser.api.isBrowserUrlAllowed
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserCommand
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserReloadDecision
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRequestKind
import io.aequicor.heartbeat.feature.browser.impl.domain.IosBrowserEngine
import io.aequicor.heartbeat.feature.browser.impl.domain.browserReloadDecision
import io.aequicor.heartbeat.feature.browser.impl.domain.isBrowserRequestAllowed
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSThread
import platform.Foundation.NSTimer
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLRequest
import platform.WebKit.WKAudiovisualMediaTypeAll
import platform.WebKit.WKFrameInfo
import platform.WebKit.WKMediaCaptureType
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationActionPolicy.WKNavigationActionPolicyAllow
import platform.WebKit.WKNavigationActionPolicy.WKNavigationActionPolicyCancel
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationResponse
import platform.WebKit.WKNavigationResponsePolicy
import platform.WebKit.WKNavigationResponsePolicy.WKNavigationResponsePolicyAllow
import platform.WebKit.WKNavigationResponsePolicy.WKNavigationResponsePolicyCancel
import platform.WebKit.WKOpenPanelParameters
import platform.WebKit.WKPermissionDecision
import platform.WebKit.WKPermissionDecision.WKPermissionDecisionDeny
import platform.WebKit.WKSecurityOrigin
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.WebKit.WKWindowFeatures
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Main-thread WebKit owner with an isolated, nonpersistent authorization session.
 *
 * JavaScript runs inside WebKit without a native application bridge. User-agent permission handling
 * stays with WebKit; media capture is denied by this delegate. Website data is never persisted.
 * On older iOS versions file selection uses the system's user-controlled WebKit picker.
 * Navigation errors are mapped to safe categories without logging URLs, titles or native diagnostics.
 */
@OptIn(ExperimentalForeignApi::class)
internal class WebKitBrowserEngine(private var onChanged: ((BrowserPage) -> Unit)?) : IosBrowserEngine {
    init {
        check(NSThread.isMainThread) { "WebKit must be created on the UI thread" }
    }

    private val log = Log.tag("WebKitBrowserEngine")
    private val view = WKWebView(
        frame = CGRectZero.readValue(),
        configuration = WKWebViewConfiguration().apply {
            websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
            defaultWebpagePreferences.allowsContentJavaScript = true
            preferences.javaScriptCanOpenWindowsAutomatically = false
            mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeAll
            allowsAirPlayForMediaPlayback = false
            allowsPictureInPictureMediaPlayback = false
        },
    )
    private var isReleased = false
    private var isStopped = false
    private var isLoading = false
    private var requestedAddress = ""
    private var committedAddress = ""
    private var failure: BrowserError? = null
    private var activeNavigation: WKNavigation? = null
    private var stateObserver: NSTimer? = null
    private var previousPage: BrowserPage? = null

    override val nativeView: Any get() = view
    private val delegate = NavigationDelegate()

    init {
        view.navigationDelegate = delegate
        view.UIDelegate = delegate
        view.allowsLinkPreview = false
        view.allowsBackForwardNavigationGestures = true
        // Same-document anchors do not produce a full navigation delegate lifecycle.
        // Read only while this surface exists, and publish only changed snapshots.
        stateObserver = NSTimer.scheduledTimerWithTimeInterval(STATE_SAMPLE_INTERVAL, repeats = true) {
            if (!isReleased) {
                val address = view.URL?.absoluteString.orEmpty()
                if (!isLoading && failure == null && isBrowserUrlAllowed(address)) {
                    requestedAddress = address
                    committedAddress = address
                }
                publish()
            }
        }
    }

    override fun execute(command: BrowserCommand) {
        onMain {
            when (command) {
                is BrowserCommand.Load -> load(command.url)
                BrowserCommand.Back -> if (view.canGoBack) navigate { view.goBack() }
                BrowserCommand.Forward -> if (view.canGoForward) navigate { view.goForward() }
                BrowserCommand.Reload -> reload()
                BrowserCommand.Stop -> stop()
            }
        }
    }

    override fun release() {
        onMain {
            isReleased = true
            onChanged = null
            stateObserver?.invalidate()
            stateObserver = null
            view.navigationDelegate = null
            view.UIDelegate = null
            view.stopLoading()
            view.configuration.userContentController.removeAllUserScripts()
            view.removeFromSuperview()
            activeNavigation = null
        }
    }

    private fun onMain(action: () -> Unit) {
        if (NSThread.isMainThread) {
            if (!isReleased) action()
        } else {
            dispatch_async(dispatch_get_main_queue()) { if (!isReleased) action() }
        }
    }

    private fun load(address: String, isReload: Boolean = false) {
        if (isLoading && requestedAddress == address && !isReload) return
        if (!isBrowserUrlAllowed(address)) {
            failure = BrowserError.UnsupportedAddress
            publish()
            return
        }
        val url = NSURL.URLWithString(address)
        if (url == null) {
            failure = BrowserError.UnsupportedAddress
            publish()
            return
        }
        requestedAddress = address
        navigate { view.loadRequest(NSURLRequest.requestWithURL(url)) }
    }

    private fun reload() {
        val reloadableAddress = if (view.URL == null) "" else committedAddress
        when (val decision = browserReloadDecision(requestedAddress, reloadableAddress)) {
            BrowserReloadDecision.Ignore -> Unit
            BrowserReloadDecision.ReloadCommitted -> navigate { view.reload() }
            is BrowserReloadDecision.LoadRequested -> load(decision.url, isReload = true)
        }
    }

    private fun stop() {
        isStopped = true
        isLoading = false
        view.stopLoading()
        publish()
    }

    private fun navigate(action: () -> WKNavigation?) {
        isStopped = false
        failure = null
        isLoading = true
        activeNavigation = action()
        publish()
    }

    private fun rejectMainNavigation() {
        failure = BrowserError.UnsupportedAddress
        isLoading = false
        publish()
    }

    @HighFrequency
    private fun publish() {
        if (isReleased) return
        val page = BrowserPage(
            url = requestedAddress,
            title = view.title.orEmpty(),
            isLoading = isLoading && !isStopped,
            isBackAvailable = view.canGoBack,
            isForwardAvailable = view.canGoForward,
            error = failure,
        )
        if (page != previousPage) {
            previousPage = page
            onChanged?.invoke(page)
        }
    }

    private inner class NavigationDelegate :
        NSObject(),
        WKNavigationDelegateProtocol,
        WKUIDelegateProtocol {
        @ObjCSignatureOverride
        override fun webView(
            webView: WKWebView,
            decidePolicyForNavigationAction: WKNavigationAction,
            decisionHandler: (WKNavigationActionPolicy) -> Unit,
        ) {
            val action = decidePolicyForNavigationAction
            val address = action.request.URL?.absoluteString.orEmpty()
            val frame = action.targetFrame
            if (isReleased || frame == null) {
                decisionHandler(WKNavigationActionPolicyCancel)
                return
            }
            val kind = if (frame.mainFrame) BrowserRequestKind.MainDocument else BrowserRequestKind.ChildDocument
            if (!isBrowserRequestAllowed(address, kind)) {
                decisionHandler(WKNavigationActionPolicyCancel)
                if (frame.mainFrame) rejectMainNavigation()
                return
            }
            if (frame.mainFrame) {
                requestedAddress = address
                isStopped = false
            }
            decisionHandler(WKNavigationActionPolicyAllow)
        }

        @ObjCSignatureOverride
        override fun webView(
            webView: WKWebView,
            decidePolicyForNavigationResponse: WKNavigationResponse,
            decisionHandler: (WKNavigationResponsePolicy) -> Unit,
        ) {
            val response = decidePolicyForNavigationResponse
            if (isReleased) {
                decisionHandler(WKNavigationResponsePolicyCancel)
                return
            }
            val status = (response.response as? NSHTTPURLResponse)?.statusCode ?: 0
            if (response.forMainFrame && (status >= HTTP_ERROR_STATUS || !response.canShowMIMEType)) {
                log.w(IllegalStateException("WebKit response status=$status displayable=${response.canShowMIMEType}")) {
                    "Main document response failed"
                }
                failure = BrowserError.LoadFailed
                isLoading = false
                publish()
            }
            decisionHandler(
                if (response.canShowMIMEType) WKNavigationResponsePolicyAllow else WKNavigationResponsePolicyCancel,
            )
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?) {
            if (isReleased || isStopped) return
            activeNavigation = didStartProvisionalNavigation
            failure = null
            isLoading = true
            publish()
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didReceiveServerRedirectForProvisionalNavigation: WKNavigation?) {
            if (isReleased || isStopped || didReceiveServerRedirectForProvisionalNavigation != activeNavigation) return
            val address = webView.URL?.absoluteString.orEmpty()
            if (isBrowserUrlAllowed(address)) requestedAddress = address
            publish()
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didCommitNavigation: WKNavigation?) {
            if (isReleased || didCommitNavigation != activeNavigation) return
            val address = webView.URL?.absoluteString.orEmpty()
            if (isBrowserUrlAllowed(address)) {
                requestedAddress = address
                committedAddress = address
            }
            publish()
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
            if (isReleased || didFinishNavigation != activeNavigation) return
            isLoading = false
            publish()
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
            failed(didFailProvisionalNavigation, withError)
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
            failed(didFailNavigation, withError)
        }

        private fun failed(navigation: WKNavigation?, error: NSError) {
            if (isReleased || isStopped || navigation != activeNavigation) return
            if (error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled) return
            log.w(IllegalStateException("WebKit navigation error code=${error.code}")) { "Main document load failed" }
            isLoading = false
            failure = BrowserError.LoadFailed
            publish()
        }

        override fun webViewWebContentProcessDidTerminate(webView: WKWebView) {
            if (isReleased) return
            log.e(IllegalStateException("WebKit renderer terminated")) { "Browser renderer unavailable" }
            isLoading = false
            failure = BrowserError.EngineUnavailable
            publish()
        }

        @ObjCSignatureOverride
        override fun webView(
            webView: WKWebView,
            createWebViewWithConfiguration: WKWebViewConfiguration,
            forNavigationAction: WKNavigationAction,
            windowFeatures: WKWindowFeatures,
        ): WKWebView? = null

        @ObjCSignatureOverride
        override fun webView(
            webView: WKWebView,
            requestMediaCapturePermissionForOrigin: WKSecurityOrigin,
            initiatedByFrame: WKFrameInfo,
            type: WKMediaCaptureType,
            decisionHandler: (WKPermissionDecision) -> Unit,
        ) {
            decisionHandler(WKPermissionDecisionDeny)
        }

        @ObjCSignatureOverride
        override fun webView(
            webView: WKWebView,
            requestDeviceOrientationAndMotionPermissionForOrigin: WKSecurityOrigin,
            initiatedByFrame: WKFrameInfo,
            decisionHandler: (WKPermissionDecision) -> Unit,
        ) {
            decisionHandler(WKPermissionDecisionDeny)
        }

        @ObjCSignatureOverride
        override fun webView(
            webView: WKWebView,
            runOpenPanelWithParameters: WKOpenPanelParameters,
            initiatedByFrame: WKFrameInfo,
            completionHandler: (List<*>?) -> Unit,
        ) {
            completionHandler(null)
        }
    }
}

private const val STATE_SAMPLE_INTERVAL = 0.25
private const val HTTP_ERROR_STATUS = 400
