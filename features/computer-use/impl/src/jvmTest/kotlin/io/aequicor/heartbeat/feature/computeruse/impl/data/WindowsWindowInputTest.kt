package io.aequicor.heartbeat.feature.computeruse.impl.data

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.ptr.IntByReference
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.FramePoint
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.lang.reflect.Proxy
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsWindowInputTest {
    @Test
    fun `click and Unicode text reach the same child with no global input API`() {
        val f = Fixture()
        f.driver.move(ScreenPoint(130, 240))
        f.driver.pressButton(InputEvent.BUTTON1_DOWN_MASK)
        f.driver.releaseButton(InputEvent.BUTTON1_DOWN_MASK)
        f.driver.typeUnicode("Я😀")
        assertTrue(f.messages.all { it.target == f.child })
        assertEquals(listOf(0x200, 0x201, 0x202, 0x102, 0x102, 0x102), f.messages.map { it.id })
        assertEquals(10 or (10 shl 16), f.messages.first().parameter)
        assertEquals("Я😀".map { it.code }, f.messages.takeLast(3).map { it.word })
        assertEquals(ScreenPoint(130, 240), f.driver.location())
    }

    @Test
    fun `drag stays on its initial child while wheel uses signed screen coordinates`() {
        val f = Fixture()
        f.driver.move(ScreenPoint(130, 240))
        f.driver.pressButton(InputEvent.BUTTON1_DOWN_MASK)
        f.driver.move(ScreenPoint(350, 340))
        assertEquals(f.child, f.messages.last().target)
        assertEquals(1, f.messages.last().word)
        assertEquals(230 or (110 shl 16), f.messages.last().parameter)
        f.driver.releaseButton(InputEvent.BUTTON1_DOWN_MASK)
        f.driver.wheel(-1)
        assertEquals(0x20A, f.messages.last().id)
        assertEquals(120 shl 16, f.messages.last().word)
        assertEquals(350 or (340 shl 16), f.messages.last().parameter)
    }

    @Test
    fun `double and triple clicks use double click message for the second press`() {
        val f = Fixture()
        f.driver.move(ScreenPoint(130, 240))
        repeat(3) {
            f.driver.pressButton(InputEvent.BUTTON3_DOWN_MASK)
            f.driver.releaseButton(InputEvent.BUTTON3_DOWN_MASK)
        }
        assertEquals(listOf(0x200, 0x204, 0x205, 0x206, 0x205, 0x204, 0x205), f.messages.map { it.id })
        f.driver.beginAction()
        f.driver.pressButton(InputEvent.BUTTON3_DOWN_MASK)
        assertEquals(0x204, f.messages.last().id)
    }

    @Test
    fun `classes without double click handling get two normal presses`() {
        val f = Fixture().apply { classStyle = 0 }
        f.driver.move(ScreenPoint(130, 240))
        repeat(2) {
            f.driver.pressButton(InputEvent.BUTTON1_DOWN_MASK)
            f.driver.releaseButton(InputEvent.BUTTON1_DOWN_MASK)
        }
        assertEquals(listOf(0x200, 0x201, 0x202, 0x201, 0x202), f.messages.map { it.id })
    }

    @Test
    fun `keyboard messages carry native virtual codes and release the original recipient`() {
        val f = Fixture()
        f.driver.pressKey(KeyEvent.VK_ENTER)
        f.focus = f.outside
        f.driver.releaseKey(KeyEvent.VK_ENTER)
        assertEquals(listOf(0x100, 0x101), f.messages.map { it.id })
        assertTrue(f.messages.all { it.target == f.child && it.word == 13 })
        assertEquals(1 or (28 shl 16), f.messages.first().parameter)
        assertEquals(1 or (28 shl 16) or (3 shl 30), f.messages.last().parameter)
        assertEquals(0x2D, windowsMessageKey(KeyEvent.VK_INSERT))
        assertEquals(0x2E, windowsMessageKey(KeyEvent.VK_DELETE))
        assertEquals(0x7C, windowsMessageKey(KeyEvent.VK_F13))
        assertEquals(0x87, windowsMessageKey(KeyEvent.VK_F24))
        assertEquals(0xBD, windowsMessageKey(KeyEvent.VK_MINUS))
        assertNull(windowsMessageKey(KeyEvent.VK_CONTROL))
        assertFalse(f.driver.supportsKeys(listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_A)))
    }

    @Test
    fun `modifier combination is refused before any message and never falls back`() = runTest {
        val f = Fixture()
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), f.devices)
        val capture = injector.forCapture(ComputerUseMode.Window(windowTarget()))
        assertFalse(capture.isForegroundRequired)
        val outcome = capture.apply(InputAction.Key(listOf("ctrl", "a"))) { null }
        assertEquals(ComputerUseFailure.UnsupportedKey, assertIs<InputOutcome.Rejected>(outcome).reason)
        assertTrue(f.messages.isEmpty())
        assertTrue(injector.forCapture(ComputerUseMode.Desktop()).isForegroundRequired)
    }

    @Test
    fun `typing controls are queued in text order and Tab is refused before typing`() = runTest {
        val f = Fixture()
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), f.devices)
        assertEquals(InputOutcome.Applied, injector.apply(InputAction.Type("a\bb\r\nc")) { null })
        assertEquals(listOf('a', '\b', 'b', '\r', 'c').map { it.code }, f.messages.map { it.word })
        assertTrue(f.messages.all { it.id == 0x102 })
        f.messages.clear()
        val outcome = injector.apply(InputAction.Type("a\tb")) { null }
        assertEquals(ComputerUseFailure.UnsupportedCharacter, assertIs<InputOutcome.Rejected>(outcome).reason)
        assertTrue(f.messages.isEmpty())
    }

    @Test
    fun `cancelling after a character stops before the next message`() = runTest {
        val f = Fixture()
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), f.devices)
        val job = Job()
        f.onMessage = { if (it == 0x102) job.cancel() }
        assertFailsWith<CancellationException> {
            withContext(job) { injector.apply(InputAction.Type("abc")) { null } }
        }
        assertEquals(listOf('a'.code), f.messages.map { it.word })
    }

    @Test
    fun `non client points and keyboard focus outside the capture are refused`() {
        val f = Fixture()
        assertFailsWith<IllegalStateException> { f.driver.move(ScreenPoint(110, 205)) }
        f.focus = f.outside
        assertFailsWith<IllegalStateException> { f.driver.typeUnicode("secret") }
        assertTrue(f.messages.isEmpty())
    }

    @Test
    fun `closed minimized disabled and replaced targets cannot receive events`() {
        listOf<(Fixture) -> Unit>(
            { it.isWindow = false },
            { it.isMinimized = true },
            { it.isEnabled = false },
            { it.pid++ },
        ).forEach { invalidate ->
            val f = Fixture()
            f.driver.move(ScreenPoint(130, 240))
            f.messages.clear()
            invalidate(f)
            assertFailsWith<IllegalStateException> { f.driver.pressButton(InputEvent.BUTTON1_DOWN_MASK) }
            assertTrue(f.messages.isEmpty())
        }
    }

    @Test
    fun `message refusal returns failure and release still runs on the same child`() = runTest {
        val f = Fixture()
        val injector = DesktopInputInjector(TestDispatchers(StandardTestDispatcher(testScheduler)), f.devices)
        f.refusedMessage = 0x201
        val outcome = injector.apply(
            InputAction.Click(FramePoint(130.0, 240.0)),
        ) { ScreenPoint(it.x.toInt(), it.y.toInt()) }
        assertEquals(ComputerUseFailure.InputRejected, assertIs<InputOutcome.Rejected>(outcome).reason)
        assertEquals(listOf(0x200, 0x201, 0x202), f.messages.map { it.id })
        assertTrue(f.messages.all { it.target == f.child })
    }

    @Test
    fun `mixed DPI uses monitor origins and refuses a window spanning maps`() {
        val displays = listOf(ScreenBounds(-1920, 0, 1280, 720, 1.5), ScreenBounds(0, 0, 1920, 1080))
        assertEquals(
            ScreenPoint(-1770, 150),
            windowsNativePoint(ScreenPoint(-1820, 100), ScreenBounds(-1900, 20, 600, 400, 1.5), displays),
        )
        assertNull(windowsNativePoint(ScreenPoint(-10, 100), ScreenBounds(-100, 20, 600, 400), displays))
    }

    @Test
    fun `native structures preserve pointer width and by value point size`() {
        assertEquals(8, WindowInputPoint().size())
        assertEquals(if (com.sun.jna.Native.POINTER_SIZE == 8) 72 else 48, WindowThreadInfo().size())
        assertEquals(WindowThreadInfo().size(), WindowThreadInfo().cbSize)
    }

    private data class Message(val target: Pointer, val id: Int, val word: Int, val parameter: Int)

    private class Fixture {
        val root = Pointer(0x123456789L)
        val child = Pointer(0x123456790L)
        val outside = Pointer(0x987654321L)
        var focus: Pointer? = child
        var pid = 123456
        var isWindow = true
        var isMinimized = false
        var isEnabled = true
        var refusedMessage: Int? = null
        var classStyle = 0x0008
        var onMessage: (Int) -> Unit = {}
        val messages = mutableListOf<Message>()
        private val users = Proxy.newProxyInstance(
            WindowInputLib::class.java.classLoader,
            arrayOf(WindowInputLib::class.java),
        ) { _, method, arguments ->
            val args = arguments.orEmpty()
            when (method.name) {
                "GetWindowThreadProcessId" -> {
                    (args[1] as IntByReference).value = pid
                    1
                }

                "IsWindow", "IsWindowVisible" -> isWindow

                "IsWindowEnabled" -> isEnabled

                "IsIconic" -> isMinimized

                "IsChild" -> args[0] == root && args[1] == child

                "GetWindowRect" -> {
                    (args[1] as NativeRect).apply {
                        left = 100
                        top = 200
                        right = 500
                        bottom = 500
                    }
                    true
                }

                "GetClientRect" -> {
                    (args[1] as NativeRect).apply {
                        right = if (args[0] == root) 400 else 200
                        bottom = if (args[0] == root) 280 else 100
                    }
                    true
                }

                "ScreenToClient" -> {
                    (args[1] as NativePoint).apply {
                        x -= if (args[0] == root) 100 else 120
                        y -= if (args[0] == root) 220 else 230
                    }
                    true
                }

                "ChildWindowFromPointEx" -> if (args[0] == root) child else args[0]

                "GetGUIThreadInfo" -> {
                    (args[1] as WindowThreadInfo).focus = focus
                    true
                }

                "MapVirtualKeyW" -> 28

                "GetClassLongW" -> classStyle

                "PostMessageW" -> {
                    messages += Message(
                        args[0] as Pointer,
                        args[1] as Int,
                        (args[2] as WPARAM).toInt(),
                        (args[3] as LPARAM).toInt(),
                    )
                    onMessage(args[1] as Int)
                    args[1] != refusedMessage
                }

                else -> error("Unexpected native API: ${method.name}")
            }
        } as WindowInputLib
        val driver = WindowsWindowInputDriver(users, root) { listOf(ScreenBounds(0, 0, 1920, 1080)) }
        val devices = object : DesktopInputDevices {
            override val isAvailable = true
            override fun create(): DesktopInputDriver = driver.also { it.beginAction() }
            override fun forWindow(mode: ComputerUseMode.Window): DesktopInputDevices = object : DesktopInputDevices {
                override val isAvailable = true
                override val isWindowScoped = true
                override fun create(): DesktopInputDriver = driver.also { it.beginAction() }
            }
        }
    }
}
