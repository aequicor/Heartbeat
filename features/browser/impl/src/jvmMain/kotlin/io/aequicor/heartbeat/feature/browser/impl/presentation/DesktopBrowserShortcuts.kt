package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.HighFrequency
import org.cef.OS
import org.cef.browser.CefBrowser
import org.cef.handler.CefKeyboardHandler.CefKeyEvent
import org.cef.handler.CefKeyboardHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.EventFlags
import java.awt.event.KeyEvent

/**
 * Chromium's tab keys pressed inside a page. Native CEF keys bypass Compose, so the tab's own client takes them before
 * the page sees them and hands them to [onShortcut]; every other key stays with the page. One press reports one
 * shortcut, also when CEF delivers both a raw and a translated key-down; holding the keys repeats it.
 * Confined to CEF's UI thread.
 */
internal class DesktopBrowserShortcuts(
    private val onShortcut: (BrowserShortcut) -> Unit,
    private val isMac: Boolean = OS.isMacintosh(),
) : CefKeyboardHandlerAdapter() {
    private var press: Press? = null

    @HighFrequency
    override fun onPreKeyEvent(browser: CefBrowser, event: CefKeyEvent, isKeyboardShortcut: BoolRef): Boolean =
        handle(event)

    // Some macOS windowed events reach only this callback; a consumed pre-event never reaches it.
    @HighFrequency
    override fun onKeyEvent(browser: CefBrowser, event: CefKeyEvent): Boolean = handle(event)

    @HighFrequency
    private fun handle(event: CefKeyEvent): Boolean = when (event.type) {
        CefKeyEvent.EventType.KEYEVENT_KEYUP -> {
            press = null
            false
        }

        // The character a consumed accelerator would type never reaches the page either.
        CefKeyEvent.EventType.KEYEVENT_CHAR -> press != null

        CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN, CefKeyEvent.EventType.KEYEVENT_KEYDOWN -> keyDown(event)

        null -> false
    }

    @HighFrequency
    private fun keyDown(event: CefKeyEvent): Boolean {
        val shortcut = shortcut(event) ?: return false
        val previous = press
        if (previous?.shortcut == shortcut && previous.type != event.type) return true
        press = Press(shortcut, event.type)
        onShortcut(shortcut)
        return true
    }

    @HighFrequency
    private fun shortcut(event: CefKeyEvent): BrowserShortcut? {
        val code = event.windows_key_code
        val key = when (code) {
            KeyEvent.VK_T -> 't'
            KeyEvent.VK_W -> 'w'
            KeyEvent.VK_TAB -> '\t'
            in KeyEvent.VK_1..KeyEvent.VK_9 -> '0' + (code - KeyEvent.VK_0)
            else -> return null
        }
        val isControl = event.modifiers and EventFlags.EVENTFLAG_CONTROL_DOWN != 0
        val isCommand = event.modifiers and EventFlags.EVENTFLAG_COMMAND_DOWN != 0
        return browserShortcut(
            key,
            isAccelerator = if (isMac) isCommand && !isControl else isControl && !isCommand,
            isControl = isControl,
            isShift = event.modifiers and EventFlags.EVENTFLAG_SHIFT_DOWN != 0,
            isAlt = event.modifiers and EventFlags.EVENTFLAG_ALT_DOWN != 0,
        )
    }

    private data class Press(val shortcut: BrowserShortcut, val type: CefKeyEvent.EventType)
}
