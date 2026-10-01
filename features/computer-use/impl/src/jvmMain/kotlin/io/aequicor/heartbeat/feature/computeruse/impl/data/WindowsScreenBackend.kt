package io.aequicor.heartbeat.feature.computeruse.impl.data

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import io.aequicor.heartbeat.feature.computeruse.api.WindowId
import io.aequicor.heartbeat.feature.computeruse.api.WindowTarget
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PixelGrid
import kotlin.coroutines.cancellation.CancellationException

/**
 * Window enumeration and window capture on Windows through `user32` and `gdi32`.
 *
 * `PrintWindow` renders a window into a memory device context, so a partially covered window is still captured
 * as it would look unobstructed. A minimized window has no content to render and is reported through
 * [WindowTarget.isMinimized]. Every entry point fails closed: a refused call yields `null` plus a warning,
 * never a partially drawn frame.
 */
internal object WindowsScreenBackend {
    private val log = Log.tag("WindowsScreenBackend")
    private val user32: User32Lib? = load(User32Lib::class.java, "user32")
    private val gdi32: Gdi32Lib? = load(Gdi32Lib::class.java, "gdi32")
    private val kernel32: Kernel32Lib? = load(Kernel32Lib::class.java, "kernel32")

    /** `true` when the native libraries were loaded. */
    val isAvailable: Boolean = user32 != null && gdi32 != null

    /** Visible top-level windows that have a title, ordered front to back. */
    fun windows(): List<WindowTarget> {
        val library = user32 ?: return emptyList()
        val found = mutableListOf<WindowTarget>()
        val callback = object : EnumWindowsProc {
            override fun callback(hwnd: Pointer, data: Pointer?): Boolean {
                describe(library, hwnd)?.let { found += it }
                return true
            }
        }
        try {
            library.EnumWindows(callback, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "window enumeration failed" }
            return emptyList()
        }
        log.d { "windows enumerated count=${found.size}" }
        return found
    }

    /** The current rectangle of a window; `null` when it was closed or has no area. */
    fun bounds(id: WindowId): ScreenBounds? {
        val library = user32 ?: return null
        val handle = handle(id) ?: return null
        val rect = NativeRect()
        if (!library.GetWindowRect(handle, rect)) return null
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        if (width <= 0 || height <= 0) return null
        return ScreenBounds(rect.left, rect.top, width, height, scale(library, handle))
    }

    /** Brings a window to the front so that injected input reaches it. */
    fun activate(id: WindowId): Boolean {
        val library = user32 ?: return false
        val handle = handle(id) ?: return false
        return try {
            library.SetForegroundWindow(handle)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "window activation failed" }
            false
        }
    }

    /** Renders a window into pixels; `null` when it is minimized, closed or refuses to be printed. */
    fun capture(id: WindowId): PixelGrid? {
        val users = user32 ?: return null
        val gdi = gdi32 ?: return null
        val handle = handle(id) ?: return null
        val rect = NativeRect()
        if (!users.GetWindowRect(handle, rect)) return null
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        if (width <= 0 || height <= 0) {
            log.w { "window capture skipped: empty rectangle" }
            return null
        }
        val screen = gdi.CreateCompatibleDC(null)
        if (screen == null) {
            log.w { "screen device context unavailable" }
            return null
        }
        val memory = gdi.CreateCompatibleDC(screen)
        if (memory == null) {
            gdi.DeleteDC(screen)
            return null
        }
        val bitmap = gdi.CreateCompatibleBitmap(screen, width, height)
        if (bitmap == null) {
            gdi.DeleteDC(memory)
            gdi.DeleteDC(screen)
            return null
        }
        val previous = gdi.SelectObject(memory, bitmap)
        var pixels: IntArray? = null
        try {
            if (renderWindow(users, handle, memory)) pixels = readBits(gdi, memory, bitmap, width, height)
        } finally {
            if (previous != null) gdi.SelectObject(memory, previous)
            gdi.DeleteObject(bitmap)
            gdi.DeleteDC(memory)
            gdi.DeleteDC(screen)
        }
        if (pixels == null) {
            log.w { "window capture produced no pixels" }
            return null
        }
        return PixelGrid(width, height, pixels)
    }

    private fun renderWindow(users: User32Lib, handle: Pointer, memory: Pointer): Boolean = try {
        users.PrintWindow(handle, memory, PRINT_FULL_CONTENT)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "PrintWindow refused" }
        false
    }

    private fun readBits(gdi: Gdi32Lib, memory: Pointer, bitmap: Pointer, width: Int, height: Int): IntArray? {
        val header = BitmapInfoHeader()
        header.biWidth = width
        // A negative height asks for a top-down bitmap, matching the row order of PixelGrid.
        header.biHeight = -height
        val bytes = ByteArray(width * height * BYTES_PER_PIXEL)
        val read = try {
            gdi.GetDIBits(memory, bitmap, 0, height, bytes, header, DIB_RGB_COLORS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "GetDIBits failed" }
            0
        }
        if (read == 0) return null
        val pixels = IntArray(width * height)
        for (index in pixels.indices) {
            val offset = index * BYTES_PER_PIXEL
            val blue = bytes[offset].toInt() and BYTE_MASK
            val green = bytes[offset + 1].toInt() and BYTE_MASK
            val red = bytes[offset + 2].toInt() and BYTE_MASK
            pixels[index] = (OPAQUE shl ALPHA_SHIFT) or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue
        }
        return pixels
    }

    private fun describe(library: User32Lib, hwnd: Pointer): WindowTarget? {
        if (!library.IsWindowVisible(hwnd)) return null
        val title = text(library, hwnd)
        if (title.isBlank()) return null
        val rect = NativeRect()
        if (!library.GetWindowRect(hwnd, rect)) return null
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        if (width <= 0 || height <= 0) return null
        return WindowTarget(
            id = WindowId(Pointer.nativeValue(hwnd).toString()),
            application = applicationOf(library, hwnd) ?: title,
            title = title,
            bounds = ScreenBounds(rect.left, rect.top, width, height, scale(library, hwnd)),
            isMinimized = library.IsIconic(hwnd),
        )
    }

    private fun text(library: User32Lib, hwnd: Pointer): String {
        val buffer = CharArray(TITLE_BUFFER_CHARS)
        val length = try {
            library.GetWindowTextW(hwnd, buffer, buffer.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "window title read failed" }
            0
        }
        return if (length <= 0) "" else String(buffer, 0, length)
    }

    private fun applicationOf(library: User32Lib, hwnd: Pointer): String? {
        val kernel = kernel32 ?: return null
        val processId = IntByReference()
        library.GetWindowThreadProcessId(hwnd, processId)
        val process = if (processId.value == 0) null else openProcess(kernel, processId.value)
        return process?.let { handle -> processName(kernel, handle) }
    }

    private fun openProcess(kernel: Kernel32Lib, processId: Int): Pointer? = try {
        kernel.OpenProcess(PROCESS_QUERY_LIMITED, false, processId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "process open failed" }
        null
    }

    private fun processName(kernel: Kernel32Lib, process: Pointer): String? = try {
        val buffer = CharArray(PATH_BUFFER_CHARS)
        val size = IntByReference(buffer.size)
        val read = kernel.QueryFullProcessImageNameW(process, 0, buffer, size)
        if (read && size.value > 0) String(buffer, 0, size.value).substringAfterLast('\\') else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "process name read failed" }
        null
    } finally {
        kernel.CloseHandle(process)
    }

    private fun scale(library: User32Lib, hwnd: Pointer): Double = try {
        val dpi = library.GetDpiForWindow(hwnd)
        if (dpi <= 0) 1.0 else dpi.toDouble() / DEFAULT_DPI
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "window dpi read failed" }
        1.0
    }

    private fun handle(id: WindowId): Pointer? {
        val value = id.value.toLongOrNull() ?: return null
        return if (value == 0L) null else Pointer.createConstant(value)
    }

    private fun <T : Library> load(type: Class<T>, name: String): T? = try {
        Native.load(name, type)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "native library $name is unavailable" }
        null
    }

    private const val PRINT_FULL_CONTENT = 2
    private const val DIB_RGB_COLORS = 0
    private const val BYTES_PER_PIXEL = 4
    private const val BYTE_MASK = 0xFF
    private const val OPAQUE = 255
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val DEFAULT_DPI = 96
    private const val PROCESS_QUERY_LIMITED = 0x1000
    private const val TITLE_BUFFER_CHARS = 1024
    private const val PATH_BUFFER_CHARS = 2048
}

/** `user32` entry points used by the Windows backend. */
@Suppress("FunctionNaming") // Win32 symbol names are fixed by the native ABI
internal interface User32Lib : StdCallLibrary {
    fun EnumWindows(callback: EnumWindowsProc, data: Pointer?): Boolean
    fun GetWindowTextW(hwnd: Pointer, buffer: CharArray, count: Int): Int
    fun GetWindowRect(hwnd: Pointer, rect: NativeRect): Boolean
    fun IsWindowVisible(hwnd: Pointer): Boolean
    fun IsIconic(hwnd: Pointer): Boolean
    fun GetWindowThreadProcessId(hwnd: Pointer, processId: IntByReference): Int
    fun SetForegroundWindow(hwnd: Pointer): Boolean
    fun PrintWindow(hwnd: Pointer, hdc: Pointer, flags: Int): Boolean
    fun GetDpiForWindow(hwnd: Pointer): Int
}

/** Window enumeration callback; returning `false` stops the walk. */
internal interface EnumWindowsProc : StdCallLibrary.StdCallCallback {
    fun callback(hwnd: Pointer, data: Pointer?): Boolean
}

/** `gdi32` entry points used by the Windows backend. */
@Suppress("FunctionNaming") // Win32 symbol names are fixed by the native ABI
internal interface Gdi32Lib : StdCallLibrary {
    fun CreateCompatibleDC(hdc: Pointer?): Pointer?
    fun CreateCompatibleBitmap(hdc: Pointer, width: Int, height: Int): Pointer?
    fun SelectObject(hdc: Pointer, obj: Pointer): Pointer?
    fun GetDIBits(
        hdc: Pointer,
        bitmap: Pointer,
        startScan: Int,
        scanLines: Int,
        bits: ByteArray,
        info: BitmapInfoHeader,
        usage: Int,
    ): Int

    fun DeleteObject(obj: Pointer): Boolean
    fun DeleteDC(hdc: Pointer): Boolean
}

/** `kernel32` entry points used to name the owning application. */
@Suppress("FunctionNaming") // Win32 symbol names are fixed by the native ABI
internal interface Kernel32Lib : StdCallLibrary {
    fun OpenProcess(access: Int, inherit: Boolean, processId: Int): Pointer?
    fun QueryFullProcessImageNameW(process: Pointer, flags: Int, buffer: CharArray, size: IntByReference): Boolean
    fun CloseHandle(handle: Pointer): Boolean
}

/** Win32 `RECT` in physical screen pixels. */
@Structure.FieldOrder("left", "top", "right", "bottom")
internal class NativeRect : Structure() {
    @JvmField
    var left: Int = 0

    @JvmField
    var top: Int = 0

    @JvmField
    var right: Int = 0

    @JvmField
    var bottom: Int = 0
}

/** Win32 `BITMAPINFOHEADER` for a 32 bpp bitmap without a color table. */
@Structure.FieldOrder(
    "biSize", "biWidth", "biHeight", "biPlanes", "biBitCount", "biCompression",
    "biSizeImage", "biXPelsPerMeter", "biYPelsPerMeter", "biClrUsed", "biClrImportant",
)
internal class BitmapInfoHeader : Structure() {
    @JvmField
    var biSize: Int = HEADER_BYTES

    @JvmField
    var biWidth: Int = 0

    @JvmField
    var biHeight: Int = 0

    @JvmField
    var biPlanes: Short = 1

    @JvmField
    var biBitCount: Short = 32

    @JvmField
    var biCompression: Int = 0

    @JvmField
    var biSizeImage: Int = 0

    @JvmField
    var biXPelsPerMeter: Int = 0

    @JvmField
    var biYPelsPerMeter: Int = 0

    @JvmField
    var biClrUsed: Int = 0

    @JvmField
    var biClrImportant: Int = 0

    private companion object {
        const val HEADER_BYTES = 40
    }
}
