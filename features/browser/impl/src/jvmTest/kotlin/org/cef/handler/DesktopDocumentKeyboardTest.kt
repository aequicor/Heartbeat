package org.cef.handler

import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentDismissal
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentKeyboard
import org.cef.browser.CefBrowser
import org.cef.handler.CefKeyboardHandler.CefKeyEvent
import org.cef.misc.BoolRef
import java.awt.event.KeyEvent
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** JCEF's immutable event constructor is package-private; no native runtime is needed to replay its callbacks. */
class DesktopDocumentKeyboardTest {
    private val browser = Proxy.newProxyInstance(
        CefBrowser::class.java.classLoader,
        arrayOf(CefBrowser::class.java),
    ) { _, _, _ -> error("Keyboard handling must not access the native browser") } as CefBrowser

    @Test
    fun `post-only native Escape in an editable field dismisses the current modal once`() {
        val queued = mutableListOf<() -> Unit>()
        val dismissal = DesktopDocumentDismissal(queued::add)
        val keyboard = DesktopDocumentKeyboard(dismissal)
        var calls = 0
        dismissal.update { calls++ }
        assertTrue(keyboard.onKeyEvent(browser, event(CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN)))
        assertFalse(keyboard.onKeyEvent(browser, event(CefKeyEvent.EventType.KEYEVENT_KEYUP)))
        assertFalse(keyboard.onKeyEvent(browser, event(CefKeyEvent.EventType.KEYEVENT_CHAR)))
        assertEquals(1, queued.size)
        queued.single().invoke()
        assertEquals(1, calls)
    }

    @Test
    fun `pre-event handles Escape while ordinary keys and unbound modal shortcuts propagate`() {
        val dismissal = DesktopDocumentDismissal { it() }
        val keyboard = DesktopDocumentKeyboard(dismissal)
        assertFalse(keyboard.onKeyEvent(browser, event(CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN)))
        var calls = 0
        dismissal.update { calls++ }
        assertFalse(keyboard.onKeyEvent(browser, event(CefKeyEvent.EventType.KEYEVENT_KEYDOWN, KeyEvent.VK_A)))
        assertTrue(keyboard.onPreKeyEvent(browser, event(CefKeyEvent.EventType.KEYEVENT_KEYDOWN), BoolRef(false)))
        assertEquals(1, calls)
    }

    private fun event(type: CefKeyEvent.EventType, key: Int = KeyEvent.VK_ESCAPE): CefKeyEvent = CefKeyEvent(
        type,
        0,
        key,
        53,
        false,
        '\u001b',
        '\u001b',
        true,
    )
}
