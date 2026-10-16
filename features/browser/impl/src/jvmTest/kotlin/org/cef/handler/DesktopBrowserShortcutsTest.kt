package org.cef.handler

import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserShortcut
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserZoomDirection
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopBrowserShortcuts
import org.cef.browser.CefBrowser
import org.cef.handler.CefKeyboardHandler.CefKeyEvent
import org.cef.misc.BoolRef
import org.cef.misc.EventFlags
import java.awt.event.KeyEvent
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** JCEF's immutable event constructor is package-private; no native runtime is needed to replay its callbacks. */
class DesktopBrowserShortcutsTest {
    private val browser = Proxy.newProxyInstance(
        CefBrowser::class.java.classLoader,
        arrayOf(CefBrowser::class.java),
    ) { _, _, _ -> error("Tab keys must not access the native browser") } as CefBrowser
    private val received = mutableListOf<BrowserShortcut>()

    @Test
    fun `host accelerators become tab shortcuts once per press and their characters stay hidden`() {
        val keys = DesktopBrowserShortcuts(received::add, isMac = false)
        val control = EventFlags.EVENTFLAG_CONTROL_DOWN
        assertTrue(keys.pre(event(CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN, KeyEvent.VK_T, control)))
        assertTrue(keys.onKeyEvent(browser, event(CefKeyEvent.EventType.KEYEVENT_KEYDOWN, KeyEvent.VK_T, control)))
        assertTrue(keys.pre(event(CefKeyEvent.EventType.KEYEVENT_CHAR, KeyEvent.VK_T, control)))
        assertFalse(keys.pre(event(CefKeyEvent.EventType.KEYEVENT_KEYUP, KeyEvent.VK_T, control)))
        assertFalse(keys.pre(event(CefKeyEvent.EventType.KEYEVENT_CHAR, KeyEvent.VK_T, 0)))
        assertTrue(keys.pre(event(CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN, KeyEvent.VK_3, control)))
        assertTrue(keys.pre(event(CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN, KeyEvent.VK_3, control)))
        val shift = EventFlags.EVENTFLAG_SHIFT_DOWN
        assertTrue(keys.pre(event(CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN, KeyEvent.VK_TAB, control or shift)))
        assertEquals(
            listOf(
                BrowserShortcut.NewTab,
                BrowserShortcut.SelectTab(3),
                BrowserShortcut.SelectTab(3),
                BrowserShortcut.PreviousTab,
            ),
            received,
        )
    }

    @Test
    fun `macOS uses Command and ordinary keys stay with the page`() {
        val keys = DesktopBrowserShortcuts(received::add, isMac = true)
        val command = EventFlags.EVENTFLAG_COMMAND_DOWN
        val control = EventFlags.EVENTFLAG_CONTROL_DOWN
        assertFalse(keys.pre(event(CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN, KeyEvent.VK_W, control)))
        assertFalse(keys.pre(event(CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN, KeyEvent.VK_C, command)))
        assertFalse(keys.pre(event(CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN, KeyEvent.VK_T, 0)))
        assertTrue(keys.pre(event(CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN, KeyEvent.VK_W, command)))
        assertEquals(listOf<BrowserShortcut>(BrowserShortcut.CloseTab), received)
    }

    @Test
    fun `zoom handles OEM and keypad keys once and respects the live flag`() {
        var enabled = true
        val keys = DesktopBrowserShortcuts(received::add, isMac = false, isZoomEnabled = { enabled })
        val control = EventFlags.EVENTFLAG_CONTROL_DOWN
        for ((code, direction) in listOf(
            0xBB to BrowserZoomDirection.In,
            0xBD to BrowserZoomDirection.Out,
            KeyEvent.VK_NUMPAD0 to BrowserZoomDirection.Reset,
        )) {
            assertTrue(keys.pre(event(CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN, code, control)))
            assertTrue(keys.pre(event(CefKeyEvent.EventType.KEYEVENT_KEYDOWN, code, control)))
            keys.pre(event(CefKeyEvent.EventType.KEYEVENT_KEYUP, code, control))
            assertEquals(BrowserShortcut.Zoom(direction), received.last())
        }
        assertEquals(3, received.size)
        enabled = false
        assertFalse(keys.pre(event(CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN, 0xBB, control)))
    }

    private fun DesktopBrowserShortcuts.pre(event: CefKeyEvent) = onPreKeyEvent(browser, event, BoolRef())

    private fun event(type: CefKeyEvent.EventType, keyCode: Int, modifiers: Int): CefKeyEvent =
        CefKeyEvent(type, modifiers, keyCode, 0, false, keyCode.toChar(), keyCode.toChar(), false)
}
