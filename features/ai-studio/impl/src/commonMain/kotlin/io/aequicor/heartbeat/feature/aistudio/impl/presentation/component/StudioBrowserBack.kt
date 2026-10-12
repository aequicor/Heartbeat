package io.aequicor.heartbeat.feature.aistudio.impl.presentation.component

import com.arkivanov.essenty.backhandler.BackCallback

/**
 * System "back" while the browser is shown. The browser is not a stack entry: it lies above the studio (and covers
 * it when expanded, as always on phones), so "back" hides it before the studio's own navigation handles the event.
 */
internal class StudioBrowserBack(onHide: () -> Unit) {
    /** Registered in the studio's back handler; enabled only while the browser is visible. */
    val callback: BackCallback = BackCallback(isEnabled = false, onBack = onHide)

    /** Follows the browser presentation: any visible mode intercepts "back". */
    fun update(mode: BrowserPaneMode) {
        callback.isEnabled = mode != BrowserPaneMode.Hidden
    }
}
