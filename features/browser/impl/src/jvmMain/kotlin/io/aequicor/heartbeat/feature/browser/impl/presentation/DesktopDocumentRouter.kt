package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefMessageRouterHandlerAdapter
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Names of the message router's page functions: random letters for each run of the app. JCEF registers router
 * functions for the renderer processes, so they may also appear in other browsers' pages, the user's tabs included:
 * a website can see an unexplained global function, but nothing in its name points to Heartbeat. Queries from other
 * browsers reach only their own client's routers.
 */
private val queryNames = desktopDocumentRouterConfig()

/** Separate anonymous renderer function names for each private channel. */
internal fun desktopDocumentRouterConfig(): CefMessageRouter.CefMessageRouterConfig =
    CefMessageRouter.CefMessageRouterConfig(randomName(), randomName())

/** 32 random lowercase letters, a valid global name. */
@OptIn(ExperimentalUuidApi::class)
private fun randomName(): String = Uuid.random().toHexString().map { 'a' + it.digitToInt(HEX) }
    .joinToString("")

private const val HEX = 16

/** Page function the page API of a bridged document calls. */
internal val desktopDocumentQueryFunction: String get() = queryNames.jsQueryFunction

/**
 * Bridge of one document browser: one-shot queries of its main frame, while that frame shows the document's origin
 * (of its address [url]), go to [calls]; anything else is refused. Added to the document browser's client before the
 * browser is created; the client disposes the native router, [close] detaches this bridge first.
 */
internal class DesktopDocumentRouter(url: String, private val calls: DesktopDocumentCalls) {
    private val log = Log.tag("DesktopDocumentRouter")
    private val handler = object : CefMessageRouterHandlerAdapter() {
        @HighFrequency
        override fun onQuery(
            browser: CefBrowser,
            frame: CefFrame,
            queryId: Long,
            request: String,
            persistent: Boolean,
            callback: CefQueryCallback,
        ): Boolean {
            if (persistent || !frame.isMain || !isDesktopDocumentFrame(url, frame.url)) {
                log.v { "Document query refused: not a one-shot query of the document" }
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

    /** Drops the page's pending calls and detaches the handler; the client then disposes the native router. */
    fun close() {
        calls.cancelAll()
        router.removeHandler(handler)
    }

    private companion object {
        const val REFUSED = "The call is not allowed"
    }
}

/**
 * A frame shows the document while its address is on the document's origin: [documentUrl] is the origin's root, so
 * paths set by `history.pushState`, queries and fragments keep the bridge, other hosts never match.
 */
internal fun isDesktopDocumentFrame(documentUrl: String, frameUrl: String?): Boolean =
    frameUrl?.startsWith(documentUrl) == true
