package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.api.isBrowserUrlAllowed
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRequestKind
import io.aequicor.heartbeat.feature.browser.impl.domain.isBrowserRequestAllowed
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefRequestContextHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse

/**
 * Checks native navigation before IO, including redirects, popups and service-worker resources.
 * Each resource handler retains its destination kind: a main frame's image is not a main document.
 * Empty bootstrap is local to the newly created browser; user commands never accept about/file schemes.
 * Neither JVM cookies nor external protocol dispatch participate in this backend.
 */
internal class DesktopBrowserPolicy(
    private val isReleased: () -> Boolean,
    private val isBootstrap: () -> Boolean,
    private val unsupported: () -> Unit,
    private val open: (String) -> Unit,
    unavailable: () -> Unit,
) {
    private val log = Log.tag("DesktopBrowserPolicy")

    val contextHandler = object : CefRequestContextHandlerAdapter() {
        override fun getResourceRequestHandler(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest,
            isNavigation: Boolean,
            isDownload: Boolean,
            requestInitiator: String?,
            disableDefaultHandling: BoolRef,
        ): CefResourceRequestHandler = resources(
            request,
            desktopBrowserRequestKind(isNavigation, frame?.isMain, request.resourceType),
            isDownload,
            disableDefaultHandling,
        )
    }

    val requestHandler = object : CefRequestHandlerAdapter() {
        override fun onBeforeBrowse(
            browser: CefBrowser,
            frame: CefFrame,
            request: CefRequest,
            userGesture: Boolean,
            isRedirect: Boolean,
        ): Boolean {
            val kind = if (frame.isMain) BrowserRequestKind.MainDocument else BrowserRequestKind.ChildDocument
            val isRejected = !allows(request.url, kind)
            if (isRejected && kind == BrowserRequestKind.MainDocument && !isReleased()) unsupported()
            return isRejected
        }

        override fun onOpenURLFromTab(
            browser: CefBrowser,
            frame: CefFrame,
            targetUrl: String,
            userGesture: Boolean,
        ): Boolean {
            popup(targetUrl)
            return true
        }

        override fun getResourceRequestHandler(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest,
            isNavigation: Boolean,
            isDownload: Boolean,
            requestInitiator: String?,
            disableDefaultHandling: BoolRef,
        ): CefResourceRequestHandler = resources(
            request,
            desktopBrowserRequestKind(isNavigation, frame?.isMain, request.resourceType),
            isDownload,
            disableDefaultHandling,
        )

        override fun onRenderProcessTerminated(
            browser: CefBrowser,
            status: org.cef.handler.CefRequestHandler.TerminationStatus,
            errorCode: Int,
            errorString: String,
        ) {
            if (!isReleased()) {
                log.e(IllegalStateException("JCEF renderer terminated: status=$status code=$errorCode")) {
                    "Desktop browser renderer unavailable"
                }
                unavailable()
            }
        }

        // Default getAuthCredentials and onCertificateError reject without bypassing TLS verification.
    }

    fun popup(url: String) {
        if (isReleased()) return
        if (isBrowserUrlAllowed(url)) open(url) else unsupported()
    }

    private fun resources(
        request: CefRequest,
        kind: BrowserRequestKind,
        isDownload: Boolean,
        disableDefaultHandling: BoolRef,
    ): CefResourceRequestHandler {
        if (isDownload || !allows(request.url, kind)) disableDefaultHandling.set(true)
        return ResourceHandler(kind, isDownload)
    }

    private inner class ResourceHandler(private val kind: BrowserRequestKind, private val isDownload: Boolean) :
        CefResourceRequestHandlerAdapter() {
        override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest): Boolean =
            isDownload || !allows(request.url, kind)

        override fun onResourceRedirect(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest,
            response: CefResponse,
            newUrl: StringRef,
        ) {
            if (!allows(newUrl.get(), kind)) {
                // The replacement cannot reach a local file or native protocol handler.
                newUrl.set("")
                if (kind == BrowserRequestKind.MainDocument && !isReleased()) unsupported()
            }
        }

        override fun onProtocolExecution(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest,
            allowOsExecution: BoolRef,
        ) {
            allowOsExecution.set(false)
        }
    }

    private fun allows(url: String?, kind: BrowserRequestKind): Boolean =
        !isReleased() && isBrowserRequestAllowed(url.orEmpty(), kind, isOwnBlank = isBootstrap())
}

/** CEF 152 identifies resources by type; frame.isMain alone identifies only the initiating frame. */
internal fun desktopBrowserRequestKind(
    isNavigation: Boolean,
    isMainFrame: Boolean?,
    resourceType: CefRequest.ResourceType?,
): BrowserRequestKind = when {
    resourceType == CefRequest.ResourceType.RT_MAIN_FRAME ||
        resourceType == CefRequest.ResourceType.RT_NAVIGATION_PRELOAD_MAIN_FRAME -> BrowserRequestKind.MainDocument

    resourceType == CefRequest.ResourceType.RT_SUB_FRAME ||
        resourceType == CefRequest.ResourceType.RT_NAVIGATION_PRELOAD_SUB_FRAME -> BrowserRequestKind.ChildDocument

    !isNavigation -> BrowserRequestKind.Subresource

    isMainFrame == false -> BrowserRequestKind.ChildDocument

    else -> BrowserRequestKind.MainDocument
}
