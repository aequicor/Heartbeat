package io.aequicor.heartbeat.feature.browser.impl.presentation

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

/** Native UI cannot open a file picker, download a file, or expose devtools outside the surface. */
internal fun installDesktopBrowserRestrictions(client: CefClient) {
    client.addDownloadHandler(object : CefDownloadHandler {
        override fun onBeforeDownload(
            browser: CefBrowser,
            downloadItem: CefDownloadItem,
            suggestedName: String,
            callback: CefBeforeDownloadCallback,
        ): Boolean = true // No Continue call: the native callback is released and the download cancelled.

        override fun onDownloadUpdated(
            browser: CefBrowser,
            downloadItem: CefDownloadItem,
            callback: CefDownloadItemCallback,
        ) {
            callback.cancel()
        }
    })
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
