package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.HighFrequency
import org.cef.OS
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefFocusHandler
import org.cef.handler.CefFocusHandler.FocusSource
import org.cef.handler.CefKeyboardHandler.CefKeyEvent
import org.cef.handler.CefKeyboardHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.EventFlags
import java.awt.event.KeyEvent

/**
 * Native CEF keys bypass Compose. Escape belongs to the current dismissible surface, while macOS editing commands
 * follow JCEF's KeyboardHandler example and target the focused frame after the page handles the key.
 * All callbacks and the press bookkeeping are confined to CEF's UI thread.
 */
internal class DesktopDocumentKeyboard(
    private val dismissal: DesktopDocumentDismissal,
    private val isMac: Boolean = OS.isMacintosh(),
    private val focusExit: DesktopDocumentFocusExit = DesktopDocumentFocusExit(),
) : CefKeyboardHandlerAdapter(),
    CefFocusHandler {
    private var editingPress: EditingPress? = null

    /** Focus can leave before key-up; the next attachment must not inherit a half-finished press. */
    override fun onTakeFocus(browser: CefBrowser, next: Boolean) {
        editingPress = null
        // Modal attachments bound AWT traversal to their document; restore their Compose overlay target.
        focusExit.request()
    }

    /** Observe CEF focus without intercepting or requesting it. */
    override fun onSetFocus(browser: CefBrowser, source: FocusSource): Boolean = false

    override fun onGotFocus(browser: CefBrowser) {
        editingPress = null
    }

    @HighFrequency
    override fun onPreKeyEvent(browser: CefBrowser, event: CefKeyEvent, isKeyboardShortcut: BoolRef): Boolean {
        releasePress(event)
        val isDismissed = dismissIfEscape(event)
        if (!isDismissed && editingCommand(event) != null) isKeyboardShortcut.set(true)
        return isDismissed
    }

    // Some macOS windowed events reach only this callback; a consumed pre-event never reaches it.
    @HighFrequency
    override fun onKeyEvent(browser: CefBrowser, event: CefKeyEvent): Boolean {
        releasePress(event)
        return dismissIfEscape(event) || editIfCommand(browser, event)
    }

    @HighFrequency
    private fun releasePress(event: CefKeyEvent) {
        if (event.type == CefKeyEvent.EventType.KEYEVENT_KEYUP) editingPress = null
    }

    @HighFrequency
    private fun dismissIfEscape(event: CefKeyEvent): Boolean =
        event.isPress && event.windows_key_code == KeyEvent.VK_ESCAPE && dismissal.request()

    @HighFrequency
    private fun editIfCommand(browser: CefBrowser, event: CefKeyEvent): Boolean {
        val command = editingCommand(event) ?: return false
        val previous = editingPress
        // A native press may report both variants. Handle the first type and consume its companion;
        // repeated events of that same type remain normal key repeats until a release arrives.
        if (previous?.command == command && previous.type != event.type) return true
        val frame = browser.focusedFrame ?: return false
        return try {
            if (!frame.isValid) return false
            command.execute(frame)
            editingPress = EditingPress(command, event.type)
            true
        } finally {
            frame.dispose()
        }
    }

    @HighFrequency
    private fun editingCommand(event: CefKeyEvent): EditingCommand? {
        if (!isMac || !event.isPress || !event.hasEditingModifier) return null
        val key = event.unmodified_character.lowercaseChar().takeIf { it in 'a'..'z' }
            ?: event.windows_key_code.toChar().lowercaseChar()
        val hasShift = event.modifiers and EventFlags.EVENTFLAG_SHIFT_DOWN != 0
        if (hasShift && key != 'z') return null
        return when (key) {
            'a' -> EditingCommand.SelectAll
            'c' -> EditingCommand.Copy
            'v' -> EditingCommand.Paste
            'x' -> EditingCommand.Cut
            'z' -> if (hasShift) EditingCommand.Redo else EditingCommand.Undo
            else -> null
        }
    }

    private data class EditingPress(val command: EditingCommand, val type: CefKeyEvent.EventType)

    private enum class EditingCommand(val execute: (CefFrame) -> Unit) {
        SelectAll(CefFrame::selectAll),
        Copy(CefFrame::copy),
        Paste(CefFrame::paste),
        Cut(CefFrame::cut),
        Undo(CefFrame::undo),
        Redo(CefFrame::redo),
    }
}

private val CefKeyEvent.isPress: Boolean
    get() = type == CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN || type == CefKeyEvent.EventType.KEYEVENT_KEYDOWN

private val CefKeyEvent.hasEditingModifier: Boolean
    get() = modifiers and EventFlags.EVENTFLAG_COMMAND_DOWN != 0 &&
        modifiers and (EventFlags.EVENTFLAG_CONTROL_DOWN or EventFlags.EVENTFLAG_ALT_DOWN) == 0
