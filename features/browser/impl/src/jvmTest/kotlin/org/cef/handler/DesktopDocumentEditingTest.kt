package org.cef.handler

import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentDismissal
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentKeyboard
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefFocusHandler.FocusSource
import org.cef.handler.CefKeyboardHandler.CefKeyEvent
import org.cef.misc.BoolRef
import org.cef.misc.EventFlags
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Replays immutable CEF callbacks, including paired and post-only native key events, without OS input. */
class DesktopDocumentEditingTest {
    @Test
    fun `each native down variant dispatches the standard Mac editing commands`() {
        val cases = mapOf('a' to "selectAll", 'c' to "copy", 'v' to "paste", 'x' to "cut", 'z' to "undo")
        listOf(RAW, DOWN).forEach { type ->
            cases.forEach { (key, command) ->
                val fixture = Fixture()
                assertTrue(fixture.post(event(key, type = type)))
                assertEquals(listOf(command), fixture.commands)
                assertEquals(1, fixture.disposals)
            }
        }
    }

    @Test
    fun `paired raw and combined events execute a paste only once in either order`() {
        listOf(listOf(RAW, DOWN), listOf(DOWN, RAW)).forEach { types ->
            val fixture = Fixture()
            types.forEach { assertTrue(fixture.post(event('v', type = it))) }
            assertEquals(listOf("paste"), fixture.commands)
            assertEquals(1, fixture.frameRequests)
        }
    }

    @Test
    fun `release rearms paired presses whether delivered before or after renderer dispatch`() {
        listOf(true, false).forEach { isPreRelease ->
            val fixture = Fixture()
            repeat(2) {
                assertTrue(fixture.post(event('v')))
                assertTrue(fixture.post(event('v', type = DOWN)))
                val release = event('v', type = UP)
                assertFalse(if (isPreRelease) fixture.pre(release) else fixture.post(release))
            }
            assertEquals(listOf("paste", "paste"), fixture.commands)
        }
    }

    @Test
    fun `losing or regaining native focus rearms editing when key release occurred elsewhere`() {
        listOf(true, false).forEach { isLeaving ->
            val fixture = Fixture()
            assertTrue(fixture.post(event('v')))
            if (isLeaving) {
                fixture.keyboard.onTakeFocus(fixture.browser, true)
            } else {
                fixture.keyboard.onGotFocus(fixture.browser)
            }
            assertFalse(fixture.keyboard.onSetFocus(fixture.browser, FocusSource.FOCUS_SOURCE_SYSTEM))
            assertTrue(fixture.post(event('v', type = DOWN)))
            assertEquals(listOf("paste", "paste"), fixture.commands)
        }
    }

    @Test
    fun `repeated down events remain repeatable without also executing their combined companions`() {
        val fixture = Fixture()
        repeat(3) {
            assertTrue(fixture.post(event('v')))
            assertTrue(fixture.post(event('v', type = DOWN)))
        }
        assertEquals(listOf("paste", "paste", "paste"), fixture.commands)
    }

    @Test
    fun `pre-event announces the shortcut but never edits before the page handles it`() {
        val fixture = Fixture()
        val shortcut = BoolRef(false)
        assertFalse(fixture.keyboard.onPreKeyEvent(fixture.browser, event('a', type = DOWN), shortcut))
        assertTrue(shortcut.get())
        assertEquals(0, fixture.frameRequests)
        assertTrue(fixture.post(event('a', type = DOWN)))
        assertEquals(listOf("selectAll"), fixture.commands)
    }

    @Test
    fun `native keycode supplies a shortcut when the event has no Latin character`() {
        listOf('\u0000', 'ф').forEach { character ->
            val fixture = Fixture()
            assertTrue(fixture.post(event(character, keyCode = 'A'.code)))
            assertEquals(listOf("selectAll"), fixture.commands)
        }
    }

    @Test
    fun `shift Z redoes while caps lock alone keeps undo`() {
        val fixture = Fixture()
        assertTrue(fixture.post(event('Z', COMMAND or EventFlags.EVENTFLAG_SHIFT_DOWN)))
        assertTrue(fixture.post(event('Z', COMMAND or EventFlags.EVENTFLAG_CAPS_LOCK_ON)))
        assertEquals(listOf("redo", "undo"), fixture.commands)
    }

    @Test
    fun `other platforms ordinary keys and unrelated chords propagate untouched`() {
        val ignored = listOf(
            event('v', 0),
            event('v', EventFlags.EVENTFLAG_CONTROL_DOWN),
            event('v', COMMAND or EventFlags.EVENTFLAG_CONTROL_DOWN),
            event('v', COMMAND or EventFlags.EVENTFLAG_ALT_DOWN),
            event('v', COMMAND or EventFlags.EVENTFLAG_SHIFT_DOWN),
            event('n'),
            event('v', type = UP),
            event('v', type = CefKeyEvent.EventType.KEYEVENT_CHAR),
        )
        ignored.forEach { key -> assertIgnored(Fixture(), key) }
        assertIgnored(Fixture(isMac = false), event('v'))
    }

    @Test
    fun `missing or invalid focused frames propagate instead of acquiring edit authority`() {
        listOf(Fixture(hasFrame = false), Fixture(isValid = false)).forEach { fixture ->
            assertFalse(fixture.post(event('v')))
            assertFalse(fixture.post(event('v', type = DOWN)))
            assertEquals(emptyList(), fixture.commands)
            assertEquals(if (fixture.hasFrame) 2 else 0, fixture.disposals)
        }
    }

    @Test
    fun `failed editing releases the frame and does not suppress a later native down event`() {
        val fixture = Fixture()
        fixture.commandFailure = IllegalStateException("native edit failed")
        assertFailsWith<IllegalStateException> { fixture.post(event('v')) }
        fixture.commandFailure = null
        assertTrue(fixture.post(event('v', type = DOWN)))
        assertEquals(listOf("paste"), fixture.commands)
        assertEquals(2, fixture.disposals)
    }

    private fun assertIgnored(fixture: Fixture, key: CefKeyEvent) {
        val shortcut = BoolRef(false)
        assertFalse(fixture.keyboard.onPreKeyEvent(fixture.browser, key, shortcut))
        assertFalse(shortcut.get())
        assertFalse(fixture.post(key))
        assertEquals(0, fixture.frameRequests)
    }

    private fun event(
        key: Char,
        modifiers: Int = COMMAND,
        type: CefKeyEvent.EventType = RAW,
        keyCode: Int = key.uppercaseChar().code,
    ): CefKeyEvent = CefKeyEvent(type, modifiers, keyCode, 0, false, key, key, true)

    private class Fixture(isMac: Boolean = true, val hasFrame: Boolean = true, isValid: Boolean = true) {
        val commands = mutableListOf<String>()
        var frameRequests = 0
        var disposals = 0
        var commandFailure: RuntimeException? = null
        val keyboard = DesktopDocumentKeyboard(DesktopDocumentDismissal { it() }, isMac)
        private val frame = Proxy.newProxyInstance(
            CefFrame::class.java.classLoader,
            arrayOf(CefFrame::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "isValid" -> isValid

                "dispose" -> {
                    disposals++
                    null
                }

                "selectAll", "copy", "paste", "cut", "undo", "redo" -> {
                    commandFailure?.let { throw it }
                    commands.add(method.name)
                    null
                }

                else -> error("Unexpected frame call: ${method.name}")
            }
        } as CefFrame
        val browser = Proxy.newProxyInstance(
            CefBrowser::class.java.classLoader,
            arrayOf(CefBrowser::class.java),
        ) { _, method, _ ->
            check(method.name == "getFocusedFrame")
            frameRequests++
            frame.takeIf { hasFrame }
        } as CefBrowser

        fun post(event: CefKeyEvent): Boolean = keyboard.onKeyEvent(browser, event)
        fun pre(event: CefKeyEvent): Boolean = keyboard.onPreKeyEvent(browser, event, BoolRef(false))
    }

    private companion object {
        const val COMMAND = EventFlags.EVENTFLAG_COMMAND_DOWN
        val RAW = CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN
        val DOWN = CefKeyEvent.EventType.KEYEVENT_KEYDOWN
        val UP = CefKeyEvent.EventType.KEYEVENT_KEYUP
    }
}
