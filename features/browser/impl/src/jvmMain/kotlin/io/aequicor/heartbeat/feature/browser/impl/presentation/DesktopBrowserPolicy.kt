package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.api.isBrowserUrlAllowed
import io.aequicor.heartbeat.feature.browser.api.normalizeBrowserUrl
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
 * Documents (main and embedded, including every redirect hop and popup) and WebSocket connections also pass the
 * tab's [guard] for its agent owner.
 */
internal class DesktopBrowserPolicy(
    private val isReleased: () -> Boolean,
    private val isBootstrap: () -> Boolean,
    private val unsupported: () -> Unit,
    private val open: (String) -> Unit,
    unavailable: () -> Unit,
    private val guard: DesktopBrowserAgentGuard,
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
        if (isBrowserUrlAllowed(url) && guard.allows(url, BrowserRequestKind.MainDocument)) open(url) else unsupported()
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
        return guard.allows(url.orEmpty(), kind)
    }
}

/**
 * Gate of one tab for its agent owner, written by the session on main and read on CEF threads.
 *
 * An unrestricted tab (the user's own, or one the user currently controls) keeps the plain navigation policy. A
 * restricted tab loads public web addresses ([isPublicWebUrl], the check `web_fetch` applies to every redirect hop)
 * and, beyond them, only the origin (scheme, host and effective port) of the address its owner approved: the agent's
 * own navigation, which the tool dispatcher asks the user about, or the page the user handed over. This holds for the
 * main document, for embedded documents (an iframe the agent would see and click) and for WebSocket connections
 * (`ws`/`wss` compare as `http`/`https`), so neither a redirect, script, meta refresh, link, popup or user command nor
 * a public page embedding or connecting to a local or private-network address reaches one in the agent's tab; the
 * user takes control of the tab to open one. Other resources and non-network schemes are left to the scheme policy.
 * Host names are not resolved, so DNS pointing at a private address is not detected, as for `web_fetch`. Origins are
 * compared as the browser spells them: an unusual spelling of an approved address (hexadecimal IPv4, mapped IPv6),
 * or one with credentials, fails closed.
 */
internal class DesktopBrowserAgentGuard {
    private data class Restriction(val approvedOrigin: String?)

    @Volatile
    private var restriction: Restriction? = null
    private val refusedNavigation = AtomicBoolean(false)
    private val refusedContent = AtomicBoolean(false)

    val isRestricted: Boolean get() = restriction != null

    fun unrestrict() {
        restriction = null
    }

    /** Restricts the tab; a non-public [approved] address adds its origin, any other value approves nothing. */
    fun restrict(approved: String?) {
        val origin = approved?.takeUnless(::isPublicWebUrl)?.let(::browserOrigin)
        restriction = Restriction(origin)
    }

    /**
     * Whether the tab may load [url] as [kind]. A refused main document counts as a refused navigation, a refused
     * embedded document or socket as refused content: only the former fails the agent's own page load.
     */
    fun allows(url: String, kind: BrowserRequestKind): Boolean {
        val current = restriction ?: return true
        val web = guardedWebUrl(url, kind) ?: return true
        if (isPublicWebUrl(web)) return true
        val isApproved = current.approvedOrigin != null && browserOrigin(web) == current.approvedOrigin
        if (!isApproved) {
            (if (kind == BrowserRequestKind.MainDocument) refusedNavigation else refusedContent).set(true)
        }
        return isApproved
    }

    /** Whether a main document was refused since the previous call. */
    fun consumeRefusal(): Boolean = refusedNavigation.getAndSet(false)

    /** Whether an embedded document or socket was refused since the previous call. */
    fun consumeContentRefusal(): Boolean = refusedContent.getAndSet(false)
}

/** HTTP(S) documents and WebSocket connections as their HTTP(S) equivalent; null for what the guard does not judge. */
private fun guardedWebUrl(url: String, kind: BrowserRequestKind): String? =
    when (url.substringBefore(':', missingDelimiterValue = "").lowercase()) {
        "http", "https" -> url.takeIf { kind != BrowserRequestKind.Subresource }
        "ws" -> "http" + url.substring("ws".length)
        "wss" -> "https" + url.substring("wss".length)
        else -> null
    }

/**
 * The origin of an HTTP(S) [url] as `scheme://host:port` with the effective port, or null when the address is not one
 * the browser accepts ([normalizeBrowserUrl] refuses credentials, ambiguous authorities and invalid hosts or ports).
 */
internal fun browserOrigin(url: String): String? {
    if (!isBrowserUrlAllowed(url)) return null
    val normalized = normalizeBrowserUrl(url) ?: return null
    val scheme = normalized.substringBefore("://").lowercase()
    val authority = normalized.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    val isIpv6 = authority.startsWith('[')
    val host = if (isIpv6) authority.substringBefore(']') + "]" else authority.substringBefore(':')
    val port = authority.removePrefix(host).removePrefix(":").toIntOrNull()
        ?: if (scheme == "https") HTTPS_PORT else HTTP_PORT
    return "$scheme://${host.lowercase().trimEnd('.')}:$port"
}

private const val HTTP_PORT = 80
private const val HTTPS_PORT = 443

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
