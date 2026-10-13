package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.Log
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefMessageRouterHandlerAdapter
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Names of the message router's page functions, random for each run of the app: JCEF registers router functions for
 * the renderer processes, so they may also appear in other browsers' pages, the user's tabs included, and a fixed name
 * would let websites recognise Heartbeat. Queries from other browsers reach only their own client's routers.
 */
@OptIn(ExperimentalUuidApi::class)
private val queryNames: CefMessageRouter.CefMessageRouterConfig = Uuid.random().toHexString()
    .let { CefMessageRouter.CefMessageRouterConfig("__hbQuery$it", "__hbCancel$it") }

/** Page function the page API of a bridged document calls. */
internal val desktopDocumentQueryFunction: String get() = queryNames.jsQueryFunction

/**
 * Bridge of one document browser: one-shot queries of its main frame, while that frame shows the document's own
 * address [url], go to [calls]; anything else is refused. Created on the CEF UI thread before the browser, added to
 * its client and released with [dispose] when the browser closes.
 */
internal class DesktopDocumentRouter(url: String, private val calls: DesktopDocumentCalls) {
    private val log = Log.tag("DesktopDocumentRouter")
    private val handler = object : CefMessageRouterHandlerAdapter() {
        override fun onQuery(
            browser: CefBrowser,
            frame: CefFrame,
            queryId: Long,
            request: String,
            persistent: Boolean,
            callback: CefQueryCallback,
        ): Boolean {
            if (persistent || !frame.isMain || !isDesktopDocumentFrame(url, frame.url)) {
                log.w { "Document query refused: not a one-shot query of the document" }
                callback.failure(0, REFUSED)
                return true
            }
            calls.start(
                queryId,
                request,
                object : DesktopDocumentReply {
                    override fun success(json: String) = callback.success(json)

                    override fun failure(message: String) = callback.failure(0, message)
                },
            )
            return true
        }

        override fun onQueryCanceled(browser: CefBrowser, frame: CefFrame, queryId: Long) = calls.cancel(queryId)
    }

    /** The native router to add to the document browser's client. */
    val router: CefMessageRouter = CefMessageRouter.create(queryNames).also { it.addHandler(handler, true) }

    /** Drops the page's pending calls and the native router. */
    fun dispose() {
        calls.cancelAll()
        router.removeHandler(handler)
        router.dispose()
    }

    private companion object {
        const val REFUSED = "The call is not allowed"
    }
}

/** A frame shows the document while its address is the document's own, with or without a fragment. */
internal fun isDesktopDocumentFrame(documentUrl: String, frameUrl: String?): Boolean =
    frameUrl == documentUrl || frameUrl?.startsWith("$documentUrl#") == true
