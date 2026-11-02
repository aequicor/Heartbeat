package io.aequicor.heartbeat.feature.computeruse.impl.data

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import io.aequicor.heartbeat.feature.computeruse.api.WindowId
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenPoint
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.coroutines.cancellation.CancellationException

/**
 * Capture-local Windows input. Only HWND messages are posted: no Robot, SendInput, SetForegroundWindow,
 * AttachThreadInput, SetFocus or global keyboard state. A native load failure leaves this route unavailable.
 *
 * PostMessage acknowledges queuing, not processing: Raw Input clients and some toolkits ignore these messages.
 * Only client-area input is supported; minimized windows are still rejected by the capture host. Modified keys
 * are refused because posting modifier messages does not establish the target thread's GetKeyState state.
 */
internal class WindowsWindowInputDevices(id: WindowId) : DesktopInputDevices {
    private val log = Log.tag("WindowsWindowInput")
    private val driver: WindowsWindowInputDriver? = try {
        val handle = id.value.toLongOrNull()?.let(::windowsHandle)
        handle?.let {
            WindowsWindowInputDriver(
                Native.load("user32", WindowInputLib::class.java),
                it,
                ::windowsDisplays,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "window input unavailable" }
        null
    } catch (e: LinkageError) {
        log.w(e) { "window input native library cannot be linked" }
        null
    }

    override val isAvailable: Boolean get() = driver != null
    override val isWindowScoped: Boolean get() = true
    override fun create(): DesktopInputDriver = checkNotNull(driver).also { it.beginAction() }
}

/** One capture's virtual pointer and last clicked control; action execution is serialized by DesktopInputInjector. */
internal class WindowsWindowInputDriver(
    private val users: WindowInputLib,
    private val root: Pointer,
    private val displays: () -> List<ScreenBounds>,
) : DesktopInputDriver {
    private val process = IntByReference().also { users.GetWindowThreadProcessId(root, it) }.value
    private var pointer: ScreenPoint? = null
    private var nativePointer: ScreenPoint? = null
    private var receiver: Pointer? = null
    private var clicked: Pointer? = null
    private var held: Pointer? = null
    private var buttons = 0
    private var presses = 0
    private var pressedKeyTarget: Pointer? = null

    fun beginAction() {
        check(buttons == 0 && pressedKeyTarget == null) { "A previous window input could not be released" }
        presses = 0
    }

    override fun location(): ScreenPoint? = pointer
    override fun pause() = Unit
    override fun idle() = Unit
    override val isUnicodeAvailable: Boolean get() = true

    override fun supportsKeys(codes: List<Int>): Boolean = codes.size == 1 && windowsMessageKey(codes.single()) != null

    override fun move(point: ScreenPoint) {
        validate(root)
        val rect = NativeRect()
        check(users.GetWindowRect(root, rect)) { "Window geometry unavailable" }
        val bounds = ScreenBounds(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top)
        val native = checkNotNull(windowsNativePoint(point, bounds, displays())) { "Ambiguous window coordinates" }
        // Check the root's client area even during a drag; non-client and out-of-window input is never posted.
        clientPoint(root, native, requiresInside = true)
        val target = held ?: hit(native)
        post(target, WM_MOUSEMOVE, buttons, packedPoint(clientPoint(target, native)))
        pointer = point
        nativePointer = native
        receiver = target
    }

    override fun pressButton(mask: Int) {
        val target = checkNotNull(receiver) { "Move to a window control before clicking" }
        val point = checkNotNull(nativePointer)
        val button = windowButton(mask)
        held = target
        clicked = target
        buttons = buttons or button.state
        val isDoubleClick = presses++ == 1 && users.GetClassLongW(target, CLASS_STYLE) and DOUBLE_CLICK_CLASS != 0
        val message = if (isDoubleClick) button.doubleClick else button.down
        post(target, message, buttons, packedPoint(clientPoint(target, point)))
    }

    override fun releaseButton(mask: Int) {
        val target = held ?: return
        val button = windowButton(mask)
        val released = buttons and button.state.inv()
        // Only clear local held state once the release has actually been queued. A failed release poisons the
        // device so that no subsequent action silently continues with a potentially pressed target button.
        post(target, button.up, released, packedPoint(clientPoint(target, checkNotNull(nativePointer))))
        buttons = released
        held = null
    }

    override fun wheel(notches: Int) {
        val target = checkNotNull(receiver) { "Move to a window control before scrolling" }
        post(target, WM_MOUSEWHEEL, (-notches * WHEEL_DELTA) shl WORD_BITS, packedPoint(checkNotNull(nativePointer)))
    }

    override fun pressKey(code: Int) = key(code, isUp = false)
    override fun releaseKey(code: Int) = key(code, isUp = true)

    private fun key(code: Int, isUp: Boolean) {
        val virtual = checkNotNull(windowsMessageKey(code)) { "Unsupported window key" }
        val scan = users.MapVirtualKeyW(virtual, MAPVK_VK_TO_VSC)
        val extended = if (code in EXTENDED_KEYS) EXTENDED_FLAG else 0
        val flags = 1 or ((scan and BYTE_MASK) shl WORD_BITS) or extended or if (isUp) KEY_UP_FLAGS else 0
        val target = if (isUp) checkNotNull(pressedKeyTarget) else keyboardTarget().also { pressedKeyTarget = it }
        post(target, if (isUp) WM_KEYUP else WM_KEYDOWN, virtual, flags)
        if (isUp) pressedKeyTarget = null
    }

    override fun supportsText(text: String): Boolean = '\t' !in text

    override fun typeUnicode(text: String, checkpoint: () -> Unit): Boolean {
        val target = keyboardTarget()
        text.forEach {
            checkpoint()
            post(target, WM_CHAR, it.code, 1)
        }
        return true
    }

    override fun typeControlCharacter(code: Int): Boolean {
        val character = when (code) {
            KeyEvent.VK_ENTER -> '\r'
            KeyEvent.VK_BACK_SPACE -> '\b'
            else -> return false
        }
        post(keyboardTarget(), WM_CHAR, character.code, 1)
        return true
    }

    private fun keyboardTarget(): Pointer {
        clicked?.let {
            validate(it)
            return it
        }
        val pid = IntByReference()
        val thread = users.GetWindowThreadProcessId(root, pid)
        val info = WindowThreadInfo()
        check(thread != 0 && users.GetGUIThreadInfo(thread, info)) { "Window keyboard target unavailable" }
        return checkNotNull(info.focus) { "Click the target control before typing" }.also(::validate)
    }

    private fun hit(point: ScreenPoint): Pointer {
        var parent = root
        repeat(MAX_CHILD_DEPTH) {
            val client = clientPoint(parent, point, requiresInside = true)
            val child = checkNotNull(
                users.ChildWindowFromPointEx(parent, WindowInputPoint(client.x, client.y), SKIP_CHILDREN),
            )
            if (child == parent) return parent
            validate(child)
            parent = child
        }
        error("Window control hierarchy exceeds the input limit")
    }

    private fun clientPoint(target: Pointer, point: ScreenPoint, requiresInside: Boolean = false): ScreenPoint {
        validate(target)
        val client = NativePoint().apply {
            x = point.x
            y = point.y
        }
        check(users.ScreenToClient(target, client)) { "Window coordinate conversion failed" }
        if (requiresInside) {
            val rect = NativeRect()
            check(
                users.GetClientRect(target, rect) && client.x in rect.left until rect.right &&
                    client.y in rect.top until rect.bottom,
            ) { "Point is outside the window client area" }
        }
        return ScreenPoint(client.x, client.y)
    }

    private fun validate(target: Pointer) {
        val pid = IntByReference()
        check(users.IsWindow(root) && users.IsWindowVisible(root) && !users.IsIconic(root)) { "Window unavailable" }
        users.GetWindowThreadProcessId(root, pid)
        check(process != 0 && pid.value == process && process.toLong() != ProcessHandle.current().pid()) {
            "Window owner changed or is the host"
        }
        check(target == root || users.IsChild(root, target)) { "Control is outside the captured window" }
        users.GetWindowThreadProcessId(target, pid)
        check(pid.value == process && users.IsWindowEnabled(target)) { "Control unavailable or owner changed" }
    }

    private fun post(target: Pointer, message: Int, word: Int, parameter: Int) {
        validate(target)
        check(users.PostMessageW(target, message, WPARAM(word.toLong() and UINT_MASK), LPARAM(parameter.toLong()))) {
            "Window message refused: error=${Native.getLastError()}"
        }
    }

    private fun packedPoint(point: ScreenPoint): Int {
        check(point.x in Short.MIN_VALUE..Short.MAX_VALUE && point.y in Short.MIN_VALUE..Short.MAX_VALUE) {
            "Window message coordinate overflow"
        }
        return (point.x and WORD_MASK) or ((point.y and WORD_MASK) shl WORD_BITS)
    }

    private companion object {
        const val WM_MOUSEMOVE = 0x0200
        const val WM_MOUSEWHEEL = 0x020A
        const val WM_KEYDOWN = 0x0100
        const val WM_KEYUP = 0x0101
        const val WM_CHAR = 0x0102
        const val WHEEL_DELTA = 120
        const val WORD_BITS = 16
        const val WORD_MASK = 0xFFFF
        const val BYTE_MASK = 0xFF
        const val UINT_MASK = 0xFFFFFFFFL
        const val EXTENDED_FLAG = 1 shl 24
        const val KEY_UP_FLAGS = (1 shl 30) or (1 shl 31)
        const val MAPVK_VK_TO_VSC = 0
        const val SKIP_CHILDREN = 0x0007
        const val MAX_CHILD_DEPTH = 32
        const val CLASS_STYLE = -26
        const val DOUBLE_CLICK_CLASS = 0x0008
        val EXTENDED_KEYS = setOf(
            KeyEvent.VK_INSERT, KeyEvent.VK_DELETE, KeyEvent.VK_HOME, KeyEvent.VK_END,
            KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT,
            KeyEvent.VK_UP, KeyEvent.VK_DOWN,
        )
    }
}

// These two tables are literal Win32 ABI values, kept beside their AWT counterparts for review.
@Suppress("MagicNumber") // Fixed message IDs and MK_* flags from winuser.h, not policy numbers.
private enum class WindowButton(val mask: Int, val down: Int, val up: Int, val doubleClick: Int, val state: Int) {
    Left(InputEvent.BUTTON1_DOWN_MASK, 0x0201, 0x0202, 0x0203, 0x0001),
    Right(InputEvent.BUTTON3_DOWN_MASK, 0x0204, 0x0205, 0x0206, 0x0002),
    Middle(InputEvent.BUTTON2_DOWN_MASK, 0x0207, 0x0208, 0x0209, 0x0010),
}

private fun windowButton(mask: Int): WindowButton = checkNotNull(WindowButton.entries.find { it.mask == mask })

/** Explicit AWT to Win32 translation: OEM and F13+ codes differ, so AWT codes must never be posted verbatim. */
internal fun windowsMessageKey(code: Int): Int? = when (code) {
    in KeyEvent.VK_A..KeyEvent.VK_Z, in KeyEvent.VK_0..KeyEvent.VK_9,
    in KeyEvent.VK_F1..KeyEvent.VK_F12,
    KeyEvent.VK_ESCAPE, KeyEvent.VK_BACK_SPACE, KeyEvent.VK_SPACE,
    KeyEvent.VK_HOME, KeyEvent.VK_END, KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN,
    KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN,
    -> code

    in KeyEvent.VK_F13..KeyEvent.VK_F24 -> WINDOWS_F13 + code - KeyEvent.VK_F13

    else -> WindowKey.entries.find { it.awt == code }?.native
}

private const val WINDOWS_F13 = 0x7C

@Suppress("MagicNumber") // Fixed VK_* values from winuser.h; AWT's OEM and editing codes are different.
private enum class WindowKey(val awt: Int, val native: Int) {
    Enter(KeyEvent.VK_ENTER, 0x0D),
    Insert(KeyEvent.VK_INSERT, 0x2D),
    Delete(KeyEvent.VK_DELETE, 0x2E),
    Semicolon(KeyEvent.VK_SEMICOLON, 0xBA),
    Equals(KeyEvent.VK_EQUALS, 0xBB),
    Comma(KeyEvent.VK_COMMA, 0xBC),
    Minus(KeyEvent.VK_MINUS, 0xBD),
    Period(KeyEvent.VK_PERIOD, 0xBE),
    Slash(KeyEvent.VK_SLASH, 0xBF),
    BackQuote(KeyEvent.VK_BACK_QUOTE, 0xC0),
    OpenBracket(KeyEvent.VK_OPEN_BRACKET, 0xDB),
    BackSlash(KeyEvent.VK_BACK_SLASH, 0xDC),
    CloseBracket(KeyEvent.VK_CLOSE_BRACKET, 0xDD),
    Quote(KeyEvent.VK_QUOTE, 0xDE),
}

/** POINT is passed by value by ChildWindowFromPointEx, not by reference. */
@Structure.FieldOrder("x", "y")
internal class WindowInputPoint(
    @JvmField var x: Int = 0,
    @JvmField var y: Int = 0,
) : Structure(),
    Structure.ByValue

/** GUITHREADINFO preserves pointer alignment on both Win32 and Win64. */
@Structure.FieldOrder("cbSize", "flags", "active", "focus", "capture", "menuOwner", "moveSize", "caret", "caretRect")
internal class WindowThreadInfo : Structure() {
    @JvmField var cbSize: Int = size()

    @JvmField var flags: Int = 0

    @JvmField var active: Pointer? = null

    @JvmField var focus: Pointer? = null

    @JvmField var capture: Pointer? = null

    @JvmField var menuOwner: Pointer? = null

    @JvmField var moveSize: Pointer? = null

    @JvmField var caret: Pointer? = null

    @JvmField var caretRect: NativeRect = NativeRect()
}

/** This ABI deliberately exposes no global input or activation functions. */
@Suppress("FunctionNaming", "TooManyFunctions") // Entry point names and the cohesive user32 ABI are fixed by Windows.
internal interface WindowInputLib : StdCallLibrary {
    fun PostMessageW(hwnd: Pointer, message: Int, word: WPARAM, parameter: LPARAM): Boolean
    fun IsWindow(hwnd: Pointer): Boolean
    fun IsWindowVisible(hwnd: Pointer): Boolean
    fun IsWindowEnabled(hwnd: Pointer): Boolean
    fun IsIconic(hwnd: Pointer): Boolean
    fun IsChild(parent: Pointer, child: Pointer): Boolean
    fun GetWindowThreadProcessId(hwnd: Pointer, processId: IntByReference): Int
    fun GetWindowRect(hwnd: Pointer, rect: NativeRect): Boolean
    fun GetClientRect(hwnd: Pointer, rect: NativeRect): Boolean
    fun ScreenToClient(hwnd: Pointer, point: NativePoint): Boolean
    fun ChildWindowFromPointEx(parent: Pointer, point: WindowInputPoint, flags: Int): Pointer?
    fun GetGUIThreadInfo(thread: Int, info: WindowThreadInfo): Boolean
    fun MapVirtualKeyW(code: Int, type: Int): Int
    fun GetClassLongW(hwnd: Pointer, index: Int): Int
}
