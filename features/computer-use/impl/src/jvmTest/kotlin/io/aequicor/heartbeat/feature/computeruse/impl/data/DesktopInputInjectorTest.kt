package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.FramePoint
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import io.aequicor.heartbeat.feature.computeruse.impl.domain.MAX_GLIDE_STEPS
import io.aequicor.heartbeat.feature.computeruse.impl.domain.MAX_TYPED_CHARS
import io.aequicor.heartbeat.feature.computeruse.impl.domain.MAX_WHEEL_NOTCHES
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenPoint
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopInputInjectorTest {
    @Test
    fun `drag progress contains every point already sent to the driver`() = runTest {
        val driver = RecordingDriver()
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        val progress = mutableListOf<ScreenPoint?>()
        val outcome = injector.applyObserved(
            InputAction.Drag(FramePoint(-100.0, 10.0), FramePoint(20.0, 130.0)),
            { ScreenPoint(it.x.toInt(), it.y.toInt()) },
            onProgress = { point ->
                progress += point
                assertEquals(progress.size, driver.movements)
            },
        )
        assertEquals(InputOutcome.Applied, outcome)
        assertEquals(ScreenPoint(-100, 10), progress.first())
        assertEquals(ScreenPoint(20, 130), progress.last())
        assertTrue(progress.size > 2)
        assertTrue(driver.heldButtons.isEmpty())
    }

    @Test
    fun `a click glides from the agent's last point and presses only on arrival`() = runTest {
        val driver = RecordingDriver(location = ScreenPoint(500, 500))
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        injector.apply(InputAction.MoveTo(FramePoint(0.0, 0.0))) { ScreenPoint(it.x.toInt(), it.y.toInt()) }
        assertEquals(1, driver.movements, "an unknown start jumps instead of crossing other windows")
        driver.movements = 0
        val progress = mutableListOf<ScreenPoint?>()
        var effectAfterMoves = -1
        val outcome = injector.applyObserved(
            InputAction.Click(FramePoint(900.0, 1200.0)),
            { ScreenPoint(it.x.toInt(), it.y.toInt()) },
            onProgress = { progress += it },
            onEffect = {
                effectAfterMoves = driver.movements
                assertTrue(driver.events.none { it.startsWith("pressButton") })
                null
            },
            area = Area,
        )
        assertEquals(InputOutcome.Applied, outcome)
        assertEquals(MAX_GLIDE_STEPS, progress.size)
        assertEquals(ScreenPoint(900, 1200), progress.last())
        assertEquals(MAX_GLIDE_STEPS, effectAfterMoves)
        val first = assertNotNull(progress.first())
        val second = assertNotNull(progress[1])
        assertTrue(first.x > second.x - first.x, "the glide eases out: the first step is the longest")
    }

    @Test
    fun `a last point outside the authorized area makes the pointer jump`() = runTest {
        val driver = RecordingDriver(location = ScreenPoint(500, 500))
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        val map: (FramePoint) -> ScreenPoint? = { ScreenPoint(it.x.toInt(), it.y.toInt()) }
        injector.applyObserved(InputAction.MoveTo(FramePoint(0.0, 0.0)), map, onProgress = { }, area = Area)
        driver.movements = 0
        // The capture moved or its ROI narrowed: the old point is no longer inside the authorized area.
        val narrowed = ScreenBounds(800, 1000, 400, 400)
        injector.applyObserved(InputAction.Click(FramePoint(900.0, 1200.0)), map, onProgress = { }, area = narrowed)
        assertEquals(1, driver.movements)
        driver.movements = 0
        injector.applyObserved(InputAction.Click(FramePoint(1000.0, 1300.0)), map, onProgress = { }, area = null)
        assertEquals(1, driver.movements, "without an authorized area the pointer never glides")
    }

    @Test
    fun `typing announces its effect before the first key`() = runTest {
        val driver = RecordingDriver()
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        var eventsBeforeEffect = -1
        injector.applyObserved(
            InputAction.Type("ab"),
            { null },
            onProgress = { },
            onEffect = {
                eventsBeforeEffect = driver.events.size
                null
            },
        )
        assertEquals(0, eventsBeforeEffect)
        assertTrue(driver.pressedKeys.isNotEmpty())
    }

    @Test
    fun `a refusal on arrival stops the click before the button is pressed`() = runTest {
        val driver = RecordingDriver()
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        val outcome = injector.applyObserved(
            InputAction.Click(FramePoint(10.0, 10.0)),
            { ScreenPoint(it.x.toInt(), it.y.toInt()) },
            onProgress = { },
            onEffect = { ComputerUseFailure.TargetNotForeground },
        )
        assertEquals(InputOutcome.Rejected(ComputerUseFailure.TargetNotForeground), outcome)
        assertTrue(driver.events.none { it.startsWith("pressButton") })
    }

    @Test
    fun `unsupported text is rejected before the first input event`() = runTest {
        val driver = RecordingDriver()
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        val result = injector.apply(InputAction.Type("echo allowed\n🙂")) { ScreenPoint(0, 0) }
        assertEquals(InputOutcome.Rejected(ComputerUseFailure.UnsupportedCharacter), result)
        assertTrue(driver.events.isEmpty())
    }

    @Test
    fun `shifted punctuation uses its base key and releases shift`() = runTest {
        val driver = RecordingDriver()
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        assertEquals(InputOutcome.Applied, injector.apply(InputAction.Type("!_)")) { null })
        assertEquals(
            listOf(
                KeyEvent.VK_SHIFT,
                KeyEvent.VK_1,
                KeyEvent.VK_SHIFT,
                KeyEvent.VK_MINUS,
                KeyEvent.VK_SHIFT,
                KeyEvent.VK_0,
            ),
            driver.pressedKeys,
        )
        assertTrue(driver.heldKeys.isEmpty())
    }

    @Test
    fun `cancelling a drag releases the button and stops further movement`() = runTest {
        val driver = RecordingDriver()
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        val job = launch(start = CoroutineStart.LAZY) {
            injector.apply(InputAction.Drag(FramePoint(0.0, 0.0), FramePoint(120.0, 120.0))) {
                ScreenPoint(it.x.toInt(), it.y.toInt())
            }
        }
        driver.onPause = { job.cancel() }
        job.start()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertEquals(2, driver.movements)
        assertTrue(driver.heldButtons.isEmpty())
        assertTrue("releaseButton:${InputEvent.BUTTON1_DOWN_MASK}" in driver.events)
    }

    @Test
    fun `cancelling drag progress stops before pressing the mouse button`() = runTest {
        val driver = RecordingDriver()
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        val job = launch(start = CoroutineStart.LAZY) {
            injector.applyObserved(
                InputAction.Drag(FramePoint(0.0, 0.0), FramePoint(120.0, 120.0)),
                { ScreenPoint(it.x.toInt(), it.y.toInt()) },
                onProgress = { currentCoroutineContext().cancel() },
            )
        }
        job.start()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertEquals(listOf("move"), driver.events)
    }

    @Test
    fun `cancelling scroll progress stops before sending the wheel event`() = runTest {
        val driver = RecordingDriver()
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        val job = launch(start = CoroutineStart.LAZY) {
            injector.applyObserved(
                InputAction.Scroll(FramePoint(0.0, 0.0), deltaY = 120),
                { ScreenPoint(it.x.toInt(), it.y.toInt()) },
                onProgress = { currentCoroutineContext().cancel() },
            )
        }
        job.start()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertEquals(listOf("move"), driver.events)
    }

    @Test
    fun `cancelling while creating the device stops before the first input event`() = runTest {
        val driver = RecordingDriver()
        val devices = object : DesktopInputDevices {
            override val isAvailable: Boolean = true
            var onCreate: () -> Unit = {}
            override fun create(): DesktopInputDriver {
                onCreate()
                return driver
            }
        }
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), devices)
        val job = launch(start = CoroutineStart.LAZY) {
            injector.apply(InputAction.MoveTo(FramePoint(0.0, 0.0))) { ScreenPoint(0, 0) }
        }
        devices.onCreate = { job.cancel() }
        job.start()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertTrue(driver.events.isEmpty())
    }

    @Test
    fun `cancelling typed input releases all keys and stops before the next character`() = runTest {
        val driver = RecordingDriver()
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        val job = launch(start = CoroutineStart.LAZY) { injector.apply(InputAction.Type("ABC")) { null } }
        driver.onPause = { job.cancel() }
        job.start()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertEquals(listOf(KeyEvent.VK_SHIFT, KeyEvent.VK_A), driver.pressedKeys)
        assertTrue(driver.heldKeys.isEmpty())
    }

    @Test
    fun `cancelling while a modifier is held releases it before returning`() = runTest {
        val driver = RecordingDriver()
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        val job = launch(start = CoroutineStart.LAZY) { injector.apply(InputAction.Type("A")) { null } }
        driver.onPressKey = { if (it == KeyEvent.VK_SHIFT) job.cancel() }
        job.start()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertEquals(listOf(KeyEvent.VK_SHIFT), driver.pressedKeys)
        assertEquals(listOf(KeyEvent.VK_SHIFT), driver.releasedKeys)
        assertTrue(driver.heldKeys.isEmpty())
    }

    @Test
    fun `a native key press failure still releases every held key`() = runTest {
        val driver = RecordingDriver()
        driver.failOnKey = KeyEvent.VK_A
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        val result = injector.apply(InputAction.Key(listOf("ctrl", "a"))) { null }
        assertEquals(InputOutcome.Rejected(ComputerUseFailure.InputRejected), result)
        assertTrue(driver.heldKeys.isEmpty())
        assertEquals(listOf(KeyEvent.VK_A, KeyEvent.VK_CONTROL), driver.releasedKeys)
    }

    @Test
    fun `unknown key names are refused with a specific reason before any input`() = runTest {
        val driver = RecordingDriver()
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        val result = injector.apply(InputAction.Key(listOf("win", "bogus"))) { null }
        assertEquals(InputOutcome.Rejected(ComputerUseFailure.UnsupportedKey), result)
        assertTrue(driver.events.isEmpty())
        assertTrue(driver.heldKeys.isEmpty())
    }

    @Test
    fun `f13 uses the extended function key range`() = runTest {
        val driver = RecordingDriver()
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        assertEquals(InputOutcome.Applied, injector.apply(InputAction.Key(listOf("f13"))) { null })
        assertEquals(listOf(KeyEvent.VK_F13), driver.pressedKeys)
    }

    @Test
    fun `unicode-capable drivers receive the exact text and control keys stay key presses`() = runTest {
        val driver = RecordingDriver(isUnicodeAvailable = true)
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        assertEquals(InputOutcome.Applied, injector.apply(InputAction.Type("привет\nмир\t!")) { null })
        assertEquals(listOf("привет", "мир", "!"), driver.typedUnicode)
        assertEquals(listOf(KeyEvent.VK_ENTER, KeyEvent.VK_TAB), driver.pressedKeys)
    }

    @Test
    fun `a refused unicode run is reported and nothing after it is sent`() = runTest {
        val driver = RecordingDriver(isUnicodeAvailable = true, isUnicodeRefused = true)
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        val result = injector.apply(InputAction.Type("ab\ncd")) { null }
        assertEquals(InputOutcome.Rejected(ComputerUseFailure.InputRejected), result)
        assertEquals(listOf("ab"), driver.typedUnicode)
        assertTrue(driver.pressedKeys.isEmpty())
    }

    @Test
    fun `line endings and backspace are key presses on the unicode path`() = runTest {
        val driver = RecordingDriver(isUnicodeAvailable = true)
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        assertEquals(InputOutcome.Applied, injector.apply(InputAction.Type("a\r\nb\bc")) { null })
        assertEquals(listOf("a", "b", "c"), driver.typedUnicode)
        assertEquals(listOf(KeyEvent.VK_ENTER, KeyEvent.VK_BACK_SPACE), driver.pressedKeys)
    }

    @Test
    fun `other control characters are refused before the first unicode event`() = runTest {
        val driver = RecordingDriver(isUnicodeAvailable = true)
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        val result = injector.apply(InputAction.Type("a\nb\u001B")) { null }
        assertEquals(InputOutcome.Rejected(ComputerUseFailure.UnsupportedCharacter), result)
        assertTrue(driver.events.isEmpty())
    }

    @Test
    fun `text over the shared length limit is refused before any input`() = runTest {
        val driver = RecordingDriver(isUnicodeAvailable = true)
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        val result = injector.apply(InputAction.Type("a".repeat(MAX_TYPED_CHARS + 1))) { null }
        assertEquals(InputOutcome.Rejected(ComputerUseFailure.InputRejected), result)
        assertTrue(driver.events.isEmpty())
    }

    @Test
    fun `text falls back to layout key codes when the driver cannot type unicode`() = runTest {
        val driver = RecordingDriver()
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        assertEquals(InputOutcome.Applied, injector.apply(InputAction.Type("AB")) { null })
        assertTrue(driver.typedUnicode.isEmpty())
        assertEquals(listOf(KeyEvent.VK_SHIFT, KeyEvent.VK_A, KeyEvent.VK_SHIFT, KeyEvent.VK_B), driver.pressedKeys)
    }

    @Test
    fun `unmappable text is refused before the first input event on the fallback path`() = runTest {
        val driver = RecordingDriver()
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        val result = injector.apply(InputAction.Type("a🙂")) { null }
        assertEquals(InputOutcome.Rejected(ComputerUseFailure.UnsupportedCharacter), result)
        assertTrue(driver.events.isEmpty())
        assertTrue(driver.heldKeys.isEmpty())
    }

    @Test
    fun `wheel scrolling sends one native event per notch`() = runTest {
        val driver = RecordingDriver()
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        val result = injector.apply(InputAction.Scroll(FramePoint(0.0, 0.0), deltaY = -120)) { ScreenPoint(0, 0) }
        assertEquals(InputOutcome.Applied, result)
        assertEquals(listOf("wheel:-1", "wheel:-1", "wheel:-1"), driver.events.filter { it.startsWith("wheel") })
    }

    @Test
    fun `wheel scrolling clamps the notch count`() = runTest {
        val driver = RecordingDriver()
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), Devices(driver))
        val result = injector.apply(InputAction.Scroll(FramePoint(0.0, 0.0), deltaY = 100_000)) { ScreenPoint(0, 0) }
        assertEquals(InputOutcome.Applied, result)
        assertEquals(MAX_WHEEL_NOTCHES, driver.events.filter { it == "wheel:1" }.size)
    }

    private class Devices(private val driver: RecordingDriver) : DesktopInputDevices {
        override val isAvailable: Boolean = true
        override fun create(): DesktopInputDriver = driver
    }

    private class RecordingDriver(
        override val isUnicodeAvailable: Boolean = false,
        private val isUnicodeRefused: Boolean = false,
        private var location: ScreenPoint? = null,
    ) : DesktopInputDriver {
        override fun location(): ScreenPoint? = location

        val events = mutableListOf<String>()
        val heldKeys = mutableSetOf<Int>()
        val heldButtons = mutableSetOf<Int>()
        val pressedKeys = mutableListOf<Int>()
        val releasedKeys = mutableListOf<Int>()
        val typedUnicode = mutableListOf<String>()
        var movements = 0
        var onPause: () -> Unit = {}
        var onPressKey: (Int) -> Unit = {}
        var failOnKey: Int? = null

        override fun move(point: ScreenPoint) {
            if (location != null) location = point
            movements++
            events += "move"
        }

        override fun pressButton(mask: Int) {
            heldButtons += mask
            events += "pressButton:$mask"
        }

        override fun releaseButton(mask: Int) {
            heldButtons -= mask
            events += "releaseButton:$mask"
        }

        override fun pressKey(code: Int) {
            heldKeys += code
            pressedKeys += code
            events += "pressKey:$code"
            onPressKey(code)
            if (code == failOnKey) throw IllegalStateException("Native key press failed")
        }

        override fun releaseKey(code: Int) {
            heldKeys -= code
            releasedKeys += code
            events += "releaseKey:$code"
        }

        override fun wheel(notches: Int) {
            events += "wheel:$notches"
        }

        override fun pause() = onPause()

        override fun idle() = Unit

        override fun typeUnicode(text: String): Boolean {
            typedUnicode += text
            events += "typeUnicode:$text"
            return !isUnicodeRefused
        }
    }

    private companion object {
        /** An authorized area that holds every point of the glide tests. */
        val Area = ScreenBounds(0, 0, 2000, 2000)
    }
}
