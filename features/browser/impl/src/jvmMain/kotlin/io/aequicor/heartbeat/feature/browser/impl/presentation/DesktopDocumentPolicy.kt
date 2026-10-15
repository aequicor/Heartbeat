package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentResourcePolicy
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.callback.CefResourceReadCallback
import org.cef.handler.CefRequestContextHandler
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse

/**
 * Native gate of one document browser: only its own address [url] loads, as the main frame's navigation, answered
 * from memory by [page] (read on every load, so a reload shows the current data); every other navigation, frame,
 * resource, redirect, link opening a tab, download and external protocol is refused before any IO by default.
 * [resourcePolicy] may admit only bounded host-fetched assets. Pop-ups, dialogs
 * and the remaining native UI are refused by the runtime's life span handler and `installDesktopBrowserRestrictions`.
 * While [isBootstrap], the new browser may show its own blank page until the runtime has configured its context.
 * [unavailable] reports a renderer that terminated while the document was shown.
 */
internal class DesktopDocumentPolicy(
    private val url: String,
    private val page: () -> ByteArray,
    private val isReleased: () -> Boolean,
    isBootstrap: () -> Boolean,
    unavailable: () -> Unit,
    private val resourcePolicy: BrowserDocumentResourcePolicy = BrowserDocumentResourcePolicy.Isolated,
    private val asset: ((String) -> CefResourceHandler)? = null,
) {
    private val log = Log.tag("DesktopDocumentPolicy")

    val contextHandler = CefRequestContextHandler { _, frame, request, isNavigation, isDownload, _, disableDefault ->
        resources(request, isNavigation, frame?.isMain, isDownload, disableDefault)
    }

    val requestHandler = object : CefRequestHandlerAdapter() {
        override fun onBeforeBrowse(
            browser: CefBrowser,
            frame: CefFrame,
            request: CefRequest,
            userGesture: Boolean,
            isRedirect: Boolean,
        ): Boolean = isReleased() || !allowsDesktopDocumentNavigation(
            url,
            request.url,
            frame.isMain,
            isRedirect,
            isBootstrap(),
        )

        override fun onOpenURLFromTab(
            browser: CefBrowser,
            frame: CefFrame,
            targetUrl: String,
            userGesture: Boolean,
        ): Boolean = true

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
            isNavigation,
            frame?.isMain,
            isDownload,
            disableDefaultHandling,
        )

        override fun onRenderProcessTerminated(
            browser: CefBrowser,
            status: CefRequestHandler.TerminationStatus,
            errorCode: Int,
            errorString: String,
        ) {
            if (!isReleased()) {
                log.e(IllegalStateException("JCEF renderer terminated: status=$status code=$errorCode")) {
                    "Desktop document renderer unavailable"
                }
                unavailable()
            }
        }

        // Default getAuthCredentials and onCertificateError reject; the document origin never reaches the network.
    }

    /** Default Chromium network handling is always disabled, including for admitted host-fetched assets. */
    private fun resources(
        request: CefRequest,
        isNavigation: Boolean,
        isMainFrame: Boolean?,
        isDownload: Boolean,
        disableDefaultHandling: BoolRef,
    ): CefResourceRequestHandler {
        disableDefaultHandling.set(true)
        val isDocument = !isReleased() && isDesktopDocumentRequest(
            url,
            request.url,
            isNavigation,
            isMainFrame,
            isDownload,
        )
        return when {
            isDocument -> Document()

            !isReleased() && asset != null && allowsDesktopDocumentAsset(
                resourcePolicy,
                DesktopDocumentAssetRequest(
                    request.url,
                    request.method,
                    request.resourceType,
                    isNavigation,
                    isMainFrame,
                    isDownload,
                ),
            ) -> ExternalAsset()

            else -> Refused
        }
    }

    private inner class Document : CefResourceRequestHandlerAdapter() {
        override fun getResourceHandler(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest,
        ): CefResourceHandler = DesktopDocumentResponse(page(), resourcePolicy)

        override fun onResourceRedirect(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest,
            response: CefResponse,
            newUrl: StringRef,
        ) = newUrl.set("")

        override fun onProtocolExecution(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest,
            allowOsExecution: BoolRef,
        ) = allowOsExecution.set(false)
    }

    /** A permitted subresource is always served by the host; default Chromium network handling remains disabled. */
    private inner class ExternalAsset : CefResourceRequestHandlerAdapter() {
        override fun getResourceHandler(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest,
        ): CefResourceHandler? = if (isReleased()) null else asset?.invoke(request.url)

        override fun onResourceRedirect(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest,
            response: CefResponse,
            newUrl: StringRef,
        ) {
            if (isReleased() || !isPotentialDocumentAssetUrl(newUrl.get())) newUrl.set("")
        }

        override fun onProtocolExecution(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest,
            allowOsExecution: BoolRef,
        ) = allowOsExecution.set(false)
    }

    private object Refused : CefResourceRequestHandlerAdapter() {
        override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest): Boolean = true

        override fun onProtocolExecution(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest,
            allowOsExecution: BoolRef,
        ) = allowOsExecution.set(false)
    }
}

/**
 * A browser shows nothing but its document: the main frame navigates only to the document's own address, without
 * redirects, and to its own blank page only while [isBootstrap].
 */
internal fun allowsDesktopDocumentNavigation(
    documentUrl: String,
    url: String?,
    isMainFrame: Boolean,
    isRedirect: Boolean,
    isBootstrap: Boolean,
): Boolean = isMainFrame && !isRedirect && (url == documentUrl || (isBootstrap && url == BLANK_PAGE))

private const val BLANK_PAGE = "about:blank"

/** Only the main frame's navigation to the document's own address is the document; never a download. */
internal fun isDesktopDocumentRequest(
    documentUrl: String,
    url: String?,
    isNavigation: Boolean,
    isMainFrame: Boolean?,
    isDownload: Boolean,
): Boolean = isNavigation && isMainFrame == true && !isDownload && url == documentUrl

/**
 * The page answered from memory: status 200, `text/html` with [desktopDocumentHeaders]; [body] starts with a UTF-8
 * byte order mark, which decides the encoding before any header or `<meta>`. Serves both the current
 * (`open`/`read`) and the legacy (`processRequest`/`readResponse`) handler protocol on CEF's IO thread.
 */
internal class DesktopDocumentResponse(
    private val body: ByteArray,
    private val resourcePolicy: BrowserDocumentResourcePolicy = BrowserDocumentResourcePolicy.Isolated,
) : CefResourceHandlerAdapter() {
    private var offset = 0

    override fun open(request: CefRequest, handleRequest: BoolRef, callback: CefCallback): Boolean {
        handleRequest.set(true)
        return true
    }

    override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
        callback.Continue()
        return true
    }

    override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef) {
        response.status = HTTP_OK
        response.statusText = "OK"
        response.mimeType = "text/html"
        response.setHeaderMap(desktopDocumentHeaders(resourcePolicy) + ("Content-Type" to "text/html; charset=utf-8"))
        responseLength.set(body.size)
    }

    override fun read(dataOut: ByteArray, bytesToRead: Int, bytesRead: IntRef, callback: CefResourceReadCallback) =
        copy(dataOut, bytesToRead, bytesRead)

    override fun readResponse(dataOut: ByteArray, bytesToRead: Int, bytesRead: IntRef, callback: CefCallback) =
        copy(dataOut, bytesToRead, bytesRead)

    /** Copies the next part of [body]; false with zero bytes once all of it was read. */
    private fun copy(dataOut: ByteArray, bytesToRead: Int, bytesRead: IntRef): Boolean {
        val count = minOf(bytesToRead, body.size - offset)
        if (count <= 0) {
            bytesRead.set(0)
            return false
        }
        body.copyInto(dataOut, destinationOffset = 0, startIndex = offset, endIndex = offset + count)
        offset += count
        bytesRead.set(count)
        return true
    }

    private companion object {
        const val HTTP_OK = 200
    }
}

/** Bytes of a document response: a UTF-8 byte order mark, then [html] without its own mark. */
internal fun desktopDocumentBody(html: String): ByteArray =
    byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + html.removePrefix("﻿").encodeToByteArray()

/** Native admission captures method, type and frame context; the host transport revalidates the full URL before IO. */
internal fun allowsDesktopDocumentAsset(
    policy: BrowserDocumentResourcePolicy,
    request: DesktopDocumentAssetRequest,
): Boolean = policy == BrowserDocumentResourcePolicy.ExternalAssets && request.isAsset &&
    request.method == "GET" && request.type in DOCUMENT_ASSET_TYPES && isPotentialDocumentAssetUrl(request.url)

internal data class DesktopDocumentAssetRequest(
    val url: String?,
    val method: String?,
    val type: CefRequest.ResourceType?,
    val isNavigation: Boolean,
    val isMainFrame: Boolean?,
    val isDownload: Boolean,
) {
    val isAsset: Boolean get() = !isNavigation && isMainFrame == true && !isDownload
}

/** Conservative transport-independent admission; the anonymous loader also parses and validates every hop. */
private fun isPotentialDocumentAssetUrl(url: String?): Boolean {
    if (url == null || url.any { it <= ' ' || it == '\\' }) return false
    if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return false
    val authority = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
    return authority.isNotEmpty() && '@' !in authority && !authority.substringBefore(':').endsWith(".invalid", true)
}

private val DOCUMENT_ASSET_TYPES = setOf(
    CefRequest.ResourceType.RT_STYLESHEET,
    CefRequest.ResourceType.RT_SCRIPT,
    CefRequest.ResourceType.RT_IMAGE,
    CefRequest.ResourceType.RT_FONT_RESOURCE,
    CefRequest.ResourceType.RT_MEDIA,
)
