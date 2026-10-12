package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefBeforeDownloadCallback
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefDownloadItem
import org.cef.callback.CefDownloadItemCallback
import org.cef.callback.CefJSDialogCallback
import org.cef.callback.CefMenuModel
import org.cef.handler.CefContextMenuHandlerAdapter
import org.cef.handler.CefDownloadHandler
import org.cef.handler.CefJSDialogHandler
import org.cef.handler.CefJSDialogHandlerAdapter
import org.cef.misc.BoolRef
import java.util.concurrent.ConcurrentHashMap

/** Native UI cannot open a file picker, download a file, or expose devtools outside the surface. */
internal fun installDesktopBrowserRestrictions(client: CefClient) {
    client.addDownloadHandler(DesktopBrowserDownloadBlocker())
    client.addDialogHandler { _, _, _, _, _, _, _, callback ->
        callback.Cancel()
        true
    }
    client.addContextMenuHandler(object : CefContextMenuHandlerAdapter() {
        override fun onBeforeContextMenu(
            browser: CefBrowser,
            frame: CefFrame,
            params: CefContextMenuParams,
            model: CefMenuModel,
        ) {
            model.clear()
        }
    })
    client.addJSDialogHandler(object : CefJSDialogHandlerAdapter() {
        override fun onJSDialog(
            browser: CefBrowser,
            originUrl: String,
            dialogType: CefJSDialogHandler.JSDialogType,
            messageText: String,
            defaultPromptText: String,
            callback: CefJSDialogCallback,
            suppressMessage: BoolRef,
        ): Boolean {
            suppressMessage.set(true)
            return false
        }

        override fun onBeforeUnloadDialog(
            browser: CefBrowser,
            messageText: String,
            isReload: Boolean,
            callback: CefJSDialogCallback,
        ): Boolean {
            callback.Continue(true, "")
            return true
        }
    })
}

/**
 * Refuses every download without a file ever reaching disk.
 *
 * Returning false from onBeforeDownload means CEF's default handling, which saves to the downloads directory in
 * Chrome runtime style, so it is not used. Returning true without Continue keeps the download waiting for a
 * target, but the JCEF callback's finalizer calls `Continue("", false)` — a save under the suggested name in the
 * default directory — once the callback is garbage collected. The callback is therefore held strongly until
 * onDownloadUpdated has cancelled the download and reports it cancelled (or complete), and only then released.
 */
internal class DesktopBrowserDownloadBlocker : CefDownloadHandler {
    private val log = Log.tag("BrowserDownloads")
    private val held = ConcurrentHashMap<Int, CefBeforeDownloadCallback>()

    /** Downloads whose target callback is still held; visible for tests. */
    val heldCount: Int get() = held.size

    override fun onBeforeDownload(
        browser: CefBrowser,
        downloadItem: CefDownloadItem,
        suggestedName: String,
        callback: CefBeforeDownloadCallback,
    ): Boolean {
        held[downloadItem.id] = callback
        log.i { "Browser download refused" }
        return true
    }

    @HighFrequency
    override fun onDownloadUpdated(
        browser: CefBrowser,
        downloadItem: CefDownloadItem,
        callback: CefDownloadItemCallback,
    ) {
        if (downloadItem.isCanceled || downloadItem.isComplete) {
            held.remove(downloadItem.id)
            log.v { "Refused browser download finished" }
        } else {
            callback.cancel()
        }
    }
}
