package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.api.isBrowserUrlAllowed
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRequestKind
import io.aequicor.heartbeat.feature.browser.impl.domain.isBrowserRequestAllowed
import io.aequicor.heartbeat.feature.searchengine.api.isPublicWebUrl
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Checks native navigation before IO, including redirects, popups and service-worker resources.
 * Each resource handler retains its destination kind: a main frame's image is not a main document.
 * Empty bootstrap is local to the newly created browser; user commands never accept about/file schemes.
 * Neither JVM cookies nor external protocol dispatch participate in this backend.
 * Main documents, including every redirect hop and popup, also pass the tab's [guard] for its agent owner.
 */
internal class DesktopBrowserPolicy(
    private val isReleased: () -> Boolean,
    private val isBootstrap: () -> Boolean,
    private val unsupported: () -> Unit,
    private val open: (String) -> Unit,
    unavailable: () -> Unit,
    private val guard: DesktopBrowserAgentGuard = DesktopBrowserAgentGuard(),
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
        if (isBrowserUrlAllowed(url) && guard.allows(url)) open(url) else unsupported()
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

    fun allows(url: String?, kind: BrowserRequestKind): Boolean {
        if (isReleased() || !isBrowserRequestAllowed(url.orEmpty(), kind, isOwnBlank = isBootstrap())) return false
        return kind != BrowserRequestKind.MainDocument || guard.allows(url.orEmpty())
    }
}

/**
 * Main-document gate of one tab for its agent owner, written by the session on main and read on CEF threads.
 *
 * An unrestricted tab (the user's own, or one the user currently controls) keeps the plain navigation policy. A
 * restricted tab loads public web addresses ([isPublicWebUrl], the check `web_fetch` applies to every redirect hop)
 * and, beyond them, only the host of the address its owner approved: the agent's own navigation, which the tool
 * dispatcher asks the user about, or the page the user handed over. A redirect, script, meta refresh, link, popup
 * or user command therefore cannot reach another local or private-network address in the agent's tab; the user takes
 * control of the tab to open one. Host names are not
 * resolved, so DNS pointing at a private address is not detected, as for `web_fetch`. Approval compares the
 * browser's canonical host: an unusual spelling of an approved address (hexadecimal IPv4, mapped IPv6) fails closed.
 */
internal class DesktopBrowserAgentGuard {
    private data class Restriction(val approvedHost: String?)

    @Volatile
    private var restriction: Restriction? = null
    private val refused = AtomicBoolean(false)

    val isRestricted: Boolean get() = restriction != null

    fun unrestrict() {
        restriction = null
    }

    /** Restricts the tab; a non-public [approved] address adds its host, any other value approves nothing. */
    fun restrict(approved: String?) {
        val host = approved?.takeIf { isBrowserUrlAllowed(it) && !isPublicWebUrl(it) }?.let(::host)
        restriction = Restriction(host)
    }

    /** Non-HTTP(S) documents are left to the scheme policy, which allows only the engine's own blank bootstrap. */
    fun allows(url: String): Boolean {
        val current = restriction ?: return true
        if (!isBrowserUrlAllowed(url) || isPublicWebUrl(url)) return true
        val isApproved = current.approvedHost != null && host(url) == current.approvedHost
        if (!isApproved) refused.set(true)
        return isApproved
    }

    /** Whether a navigation was refused since the previous call. */
    fun consumeRefusal(): Boolean = refused.getAndSet(false)

    private fun host(url: String): String {
        val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        val host = if (authority.startsWith('[')) {
            authority.substringBefore(']') + "]"
        } else {
            authority.substringBefore(':')
        }
        return host.lowercase().trimEnd('.')
    }
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
