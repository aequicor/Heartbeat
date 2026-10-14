package io.aequicor.heartbeat.feature.browser.impl.presentation

import org.cef.browser.CefBrowser
import org.cef.handler.CefKeyboardHandler.CefKeyEvent
import org.cef.handler.CefKeyboardHandlerAdapter
import org.cef.misc.BoolRef
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/** A modal callback belongs to one attachment generation, including while an Escape dispatch is queued on EDT. */
internal class DesktopDocumentDismissal(
    private val dispatch: (() -> Unit) -> Unit = { action -> SwingUtilities.invokeLater(action) },
) {
    /** Identity is the attachment generation even when two generations share the same callback. */
    private class Target(private val action: () -> Unit) {
        fun dismiss() = action()
    }

    private val current = AtomicReference<Target?>()

    fun update(action: (() -> Unit)?) {
        current.set(action?.let(::Target))
    }

    /** Returns false outside a modal; stale events never acquire the replacement attachment's authority. */
    fun request(): Boolean {
        val target = current.get() ?: return false
        dispatch { if (current.get() === target) target.dismiss() }
        return true
    }
}

/** CEF owns keyboard focus inside its native view, so Compose alone cannot handle the modal's Escape shortcut. */
internal class DesktopDocumentKeyboard(private val dismissal: DesktopDocumentDismissal) : CefKeyboardHandlerAdapter() {
    override fun onPreKeyEvent(browser: CefBrowser, event: CefKeyEvent, isKeyboardShortcut: BoolRef): Boolean {
        val isPress = event.type == CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN ||
            event.type == CefKeyEvent.EventType.KEYEVENT_KEYDOWN
        return isPress && event.windows_key_code == KeyEvent.VK_ESCAPE && dismissal.request()
    }
}
