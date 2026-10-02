package io.aequicor.heartbeat.feature.computeruse.impl.data

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import io.aequicor.heartbeat.feature.computeruse.api.WindowId
import io.aequicor.heartbeat.feature.computeruse.api.WindowTarget
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PixelGrid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.coroutines.cancellation.CancellationException

/**
 * Window enumeration and window capture on macOS.
 *
 * Windows are listed through `CGWindowListCopyWindowInfo`, which requires the Screen Recording permission; a
 * single window is rendered by the system `screencapture` tool, which captures it even when another window
 * covers it. Input is not injected here: macOS confines keyboard and mouse events to the frontmost window, so
 * the host reports whether the target is already frontmost instead of pretending it activated it.
 */
internal object MacOsScreenBackend {
    private val log = Log.tag("MacOsScreenBackend")
    private val graphics: CoreGraphicsLib? = load(CoreGraphicsLib::class.java, "CoreGraphics")
    private val foundation: CoreFoundationLib? = load(CoreFoundationLib::class.java, "CoreFoundation")
    private val services: ApplicationServicesLib? = load(ApplicationServicesLib::class.java, "ApplicationServices")
    private val keys: WindowKeys? = keysOf(graphics, foundation)
    private val clientGeometry: MacClientGeometry? = if (services != null && foundation != null) {
        MacClientGeometry(services, foundation)
    } else {
        null
    }

    /** `true` when the frameworks and their window list keys were resolved. */
    val isAvailable: Boolean = graphics != null && foundation != null && keys != null

    /** `true` when the process may read the screen content of other applications. */
    fun hasScreenRecording(): Boolean =
        callBoolean("screen recording preflight") { graphics?.CGPreflightScreenCaptureAccess() }

    /** Asks the system for the Screen Recording permission; the user answers in System Settings. */
    fun requestScreenRecording(): Boolean =
        callBoolean("screen recording request") { graphics?.CGRequestScreenCaptureAccess() }

    /** `true` when the process may post keyboard and mouse events. */
    fun hasAccessibility(): Boolean = callBoolean("accessibility probe") { services?.AXIsProcessTrusted() }

    /** On-screen normal windows, ordered front to back. */
    fun windows(): List<WindowTarget> {
        val list = info() ?: return emptyList()
        val found = mutableListOf<WindowTarget>()
        try {
            entries(list).mapNotNullTo(found) { describe(it) }
        } finally {
            release(list)
        }
        log.d { "windows enumerated count=${found.size}" }
        return found
    }

    /** Direct CGWindowID lookup includes off-screen peers, so own-process rejection precedes presentation hiding. */
    fun resolve(id: WindowId): WindowTarget? {
        val identifier = id.value.toIntOrNull() ?: return null
        val list = info(INCLUDE_WINDOW, identifier) ?: return null
        return try {
            entries(list).singleOrNull()?.let { entry ->
                val found = keys ?: return@let null
                if (number(entry, found.ownerPid).toLong() == ProcessHandle.current().pid()) {
                    WindowTarget(id, "Heartbeat", "", ScreenBounds(0, 0, 1, 1), isSelfOwned = true)
                } else {
                    describe(entry)
                }
            }
        } finally {
            release(list)
        }
    }

    /** The current rectangle of a window; `null` when it is gone or off-screen. */
    fun bounds(id: WindowId): ScreenBounds? = resolve(id)?.bounds

    /** `true` when this window is the frontmost normal window; macOS input reaches only that one. */
    fun isFrontmost(id: WindowId): Boolean {
        val found = keys ?: return false
        val list = info() ?: return false
        return try {
            val first = entries(list).firstOrNull { number(it, found.layer).toInt() == NORMAL_LAYER }
            first != null && number(first, found.number).toLong().toString() == id.value
        } finally {
            release(list)
        }
    }

    /** Renders one window into pixels with the system capture tool; `null` on refusal or timeout. */
    suspend fun capture(id: WindowId, clientBounds: ScreenBounds? = null): PixelGrid? {
        val identifier = id.value.toLongOrNull() ?: return null
        if (identifier == 0L) return null
        val windowBounds = if (clientBounds == null) null else bounds(id) ?: return null
        val file = try {
            Files.createTempFile(WINDOW_CAPTURE_PREFIX, PNG_EXTENSION)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "temporary capture file unavailable" }
            return null
        }
        return try {
            if (!runCapture(identifier, file)) {
                null
            } else {
                decode(file)?.let { pixels ->
                    if (clientBounds != null && windowBounds != null) {
                        cropClientPixels(pixels, windowBounds, clientBounds)
                    } else {
                        pixels
                    }
                }
            }
        } finally {
            deleteQuietly(file)
        }
    }

    private fun keysOf(graphics: CoreGraphicsLib?, foundation: CoreFoundationLib?): WindowKeys? {
        if (graphics == null || foundation == null) return null
        return try {
            WindowKeys(foundation)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "window list keys are unavailable" }
            null
        }
    }

    private suspend fun runCapture(identifier: Long, file: Path): Boolean {
        val command = listOf(
            SCREEN_CAPTURE_TOOL,
            SILENT_FLAG,
            NO_SHADOW_FLAG,
            WINDOW_FLAG,
            identifier.toString(),
            file.toString(),
        )
        return runDesktopProcess(command, CAPTURE_TIMEOUT_SECONDS * MILLIS_PER_SECOND)
    }

    private fun decode(file: Path): PixelGrid? {
        val image: BufferedImage? = try {
            ImageIO.read(file.toFile())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "captured frame is unreadable" }
            null
        }
        if (image == null || image.width <= 0 || image.height <= 0) return null
        val pixels = IntArray(image.width * image.height)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                pixels[y * image.width + x] = image.getRGB(x, y)
            }
        }
        return PixelGrid(image.width, image.height, pixels)
    }

    private fun deleteQuietly(file: Path) {
        try {
            Files.deleteIfExists(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "temporary capture file delete failed" }
        }
    }

    private fun info(options: Int = ON_SCREEN_EXCLUDING_DESKTOP, id: Int = NULL_WINDOW): Pointer? {
        val library = graphics ?: return null
        return try {
            library.CGWindowListCopyWindowInfo(options, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "window list copy failed" }
            null
        }
    }

    private fun entries(list: Pointer): List<Pointer> {
        val library = foundation ?: return emptyList()
        val count = try {
            library.CFArrayGetCount(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "window list count failed" }
            return emptyList()
        }
        if (count <= 0 || count > MAX_WINDOWS) return emptyList()
        val found = ArrayList<Pointer>(count.toInt())
        for (index in 0 until count) {
            val entry = try {
                library.CFArrayGetValueAtIndex(list, index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "window list entry read failed index=$index" }
                null
            }
            if (entry != null) found += entry
        }
        return found
    }

    private fun describe(entry: Pointer): WindowTarget? {
        val found = keys ?: return null
        if (number(entry, found.layer).toInt() != NORMAL_LAYER) return null
        val identifier = number(entry, found.number).toLong()
        if (identifier == 0L) return null
        val owner = string(entry, found.owner)
        val title = string(entry, found.name)
        if (title.isNullOrBlank() && owner.isNullOrBlank()) return null
        val bounds = boundsOf(entry, found) ?: return null
        val processId = number(entry, found.ownerPid).toInt()
        val isSelfOwned = processId.toLong() == ProcessHandle.current().pid()
        return WindowTarget(
            id = WindowId(identifier.toString()),
            application = owner ?: title.orEmpty(),
            title = title.orEmpty(),
            bounds = bounds,
            isMinimized = value(entry, found.onScreen)?.let { foundation?.CFBooleanGetValue(it) != true } ?: true,
            isSelfOwned = isSelfOwned,
            clientBounds = if (isSelfOwned) null else clientGeometry?.bounds(processId, bounds),
        )
    }

    private fun boundsOf(entry: Pointer, found: WindowKeys): ScreenBounds? {
        val box = value(entry, found.bounds) ?: return null
        val x = number(box, found.x).toInt()
        val y = number(box, found.y).toInt()
        val width = number(box, found.width).toInt()
        val height = number(box, found.height).toInt()
        if (width <= 0 || height <= 0) return null
        return ScreenBounds(x, y, width, height)
    }

    private fun value(dictionary: Pointer, key: Pointer?): Pointer? {
        val library = foundation ?: return null
        if (key == null) return null
        return try {
            library.CFDictionaryGetValue(dictionary, key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "window property read failed" }
            null
        }
    }

    private fun number(dictionary: Pointer, key: Pointer?): Double {
        val reference = value(dictionary, key) ?: return 0.0
        val library = foundation ?: return 0.0
        val storage = Memory(DOUBLE_BYTES)
        val isRead = try {
            library.CFNumberGetValue(reference, FLOAT64_TYPE, storage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "window number read failed" }
            false
        }
        return if (isRead) storage.getDouble(0) else 0.0
    }

    private fun string(dictionary: Pointer, key: Pointer?): String? {
        val reference = value(dictionary, key) ?: return null
        val library = foundation ?: return null
        val length = try {
            library.CFStringGetLength(reference)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "window string length read failed" }
            return null
        }
        if (length <= 0 || length > MAX_TEXT_LENGTH) return null
        val capacity = length * UTF8_MAX_BYTES + 1
        val buffer = ByteArray(capacity.toInt())
        val isCopied = try {
            library.CFStringGetCString(reference, buffer, capacity, UTF8_ENCODING)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "window string read failed" }
            false
        }
        if (!isCopied) return null
        val end = buffer.indexOf(0)
        return if (end < 0) buffer.decodeToString() else buffer.decodeToString(0, end)
    }

    private fun release(pointer: Pointer) {
        try {
            foundation?.CFRelease(pointer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "window list release failed" }
        }
    }

    private fun callBoolean(operation: String, call: () -> Boolean?): Boolean = try {
        call() == true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "$operation failed" }
        false
    }

    private fun <T : Library> load(type: Class<T>, name: String): T? = try {
        Native.load(name, type)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "framework $name is unavailable" }
        null
    }

    private const val INCLUDE_WINDOW = 8
    private const val ON_SCREEN_EXCLUDING_DESKTOP = 1 or 16
    private const val NULL_WINDOW = 0
    private const val NORMAL_LAYER = 0
    private const val FLOAT64_TYPE = 13
    private const val UTF8_ENCODING = 0x08000100
    private const val UTF8_MAX_BYTES = 4L
    private const val DOUBLE_BYTES = 8L
    private const val CAPTURE_TIMEOUT_SECONDS = 10L
    private const val MILLIS_PER_SECOND = 1000L
    private const val MAX_WINDOWS = 4096L
    private const val MAX_TEXT_LENGTH = 4096L
    private const val SCREEN_CAPTURE_TOOL = "/usr/sbin/screencapture"
    private const val SILENT_FLAG = "-x"
    private const val NO_SHADOW_FLAG = "-o"
    private const val WINDOW_FLAG = "-l"
    private const val WINDOW_CAPTURE_PREFIX = "heartbeat-window"
    private const val PNG_EXTENSION = ".png"
}

/** CoreGraphics entry points used by the macOS backend. */
@Suppress("FunctionNaming") // CoreGraphics symbol names are fixed by the framework ABI
internal interface CoreGraphicsLib : Library {
    fun CGWindowListCopyWindowInfo(option: Int, relativeToWindow: Int): Pointer?
    fun CGPreflightScreenCaptureAccess(): Boolean
    fun CGRequestScreenCaptureAccess(): Boolean
}

/** CoreFoundation entry points used to read the window list. */
@Suppress("FunctionNaming") // CoreFoundation symbol names are fixed by the framework ABI
internal interface CoreFoundationLib : Library {
    fun CFGetTypeID(value: Pointer): Long
    fun CFArrayGetTypeID(): Long
    fun CFBooleanGetValue(value: Pointer): Boolean
    fun CFArrayGetCount(array: Pointer): Long
    fun CFArrayGetValueAtIndex(array: Pointer, index: Long): Pointer?
    fun CFDictionaryGetValue(dictionary: Pointer, key: Pointer): Pointer?
    fun CFNumberGetValue(number: Pointer, type: Int, valueOut: Pointer): Boolean
    fun CFStringGetLength(string: Pointer): Long
    fun CFStringGetCString(string: Pointer, buffer: ByteArray, size: Long, encoding: Int): Boolean
    fun CFStringCreateWithCString(allocator: Pointer?, value: String, encoding: Int): Pointer?
    fun CFRelease(value: Pointer)
}

/** ApplicationServices entry point used to probe the Accessibility permission. */
@Suppress("FunctionNaming") // Accessibility symbol names are fixed by the framework ABI
internal interface ApplicationServicesLib : Library {
    fun AXIsProcessTrusted(): Boolean
    fun AXUIElementCreateApplication(pid: Int): Pointer?
    fun AXUIElementSetMessagingTimeout(element: Pointer, timeoutInSeconds: Float): Int
    fun AXUIElementCopyAttributeValue(
        element: Pointer,
        attribute: Pointer,
        value: com.sun.jna.ptr.PointerByReference,
    ): Int
    fun AXValueGetType(value: Pointer): Int
    fun AXValueGetValue(value: Pointer, type: Int, result: Pointer): Boolean
}

/**
 * The window list dictionary keys, resolved once per process.
 *
 * Exported keys are read through their global variable address and the bounds keys are created as CoreFoundation
 * strings, so a renamed key on a future macOS release disables window capture instead of crashing the host.
 */
internal class WindowKeys(foundation: CoreFoundationLib) {
    /** `kCGWindowNumber`: the window identity used by the capture tool. */
    val number: Pointer? = global(WINDOW_NUMBER_KEY)

    /** `kCGWindowName`: the window title, absent for applications that hide it. */
    val name: Pointer? = global(WINDOW_NAME_KEY)

    /** `kCGWindowOwnerName`: the owning application. */
    val owner: Pointer? = global(WINDOW_OWNER_KEY)
    val ownerPid: Pointer? = global("kCGWindowOwnerPID")
    val onScreen: Pointer? = global("kCGWindowIsOnscreen")

    /** `kCGWindowLayer`: normal application windows are layer zero. */
    val layer: Pointer? = global(WINDOW_LAYER_KEY)

    /** `kCGWindowBounds`: the frame rectangle in points. */
    val bounds: Pointer? = global(WINDOW_BOUNDS_KEY)

    /** The `X` key of a bounds dictionary. */
    val x: Pointer? = literal(foundation, BOUNDS_XKEY)

    /** The `Y` key of a bounds dictionary. */
    val y: Pointer? = literal(foundation, BOUNDS_YKEY)

    /** The `Width` key of a bounds dictionary. */
    val width: Pointer? = literal(foundation, BOUNDS_WIDTH_KEY)

    /** The `Height` key of a bounds dictionary. */
    val height: Pointer? = literal(foundation, BOUNDS_HEIGHT_KEY)

    private fun global(name: String): Pointer? = try {
        NativeLibrary.getInstance(CORE_GRAPHICS_FRAMEWORK).getGlobalVariableAddress(name).getPointer(0)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.tag("MacOsScreenBackend").w(e) { "window list key $name is unavailable" }
        null
    }

    private fun literal(foundation: CoreFoundationLib, value: String): Pointer? = try {
        foundation.CFStringCreateWithCString(null, value, UTF8_ENCODING)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.tag("MacOsScreenBackend").w(e) { "bounds key $value is unavailable" }
        null
    }

    private companion object {
        const val CORE_GRAPHICS_FRAMEWORK = "CoreGraphics"
        const val UTF8_ENCODING = 0x08000100
        const val WINDOW_NUMBER_KEY = "kCGWindowNumber"
        const val WINDOW_NAME_KEY = "kCGWindowName"
        const val WINDOW_OWNER_KEY = "kCGWindowOwnerName"
        const val WINDOW_LAYER_KEY = "kCGWindowLayer"
        const val WINDOW_BOUNDS_KEY = "kCGWindowBounds"
        const val BOUNDS_XKEY = "X"
        const val BOUNDS_YKEY = "Y"
        const val BOUNDS_WIDTH_KEY = "Width"
        const val BOUNDS_HEIGHT_KEY = "Height"
    }
}

/**
 * Runs a short native desktop command with a cancellable wait and a cleanup barrier. The output is discarded
 * to avoid filling a pipe, and cancellation/timeout terminate the child before its capture file is removed.
 * Callers run this backend on their injected IO dispatcher.
 */
internal suspend fun runDesktopProcess(
    command: List<String>,
    timeoutMillis: Long,
    start: (List<String>) -> Process = { arguments ->
        ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    },
): Boolean {
    val log = Log.tag("DesktopProcess")
    currentCoroutineContext().ensureActive()
    val process = try {
        start(command)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "desktop command could not start" }
        return false
    }
    return try {
        val finished = withTimeoutOrNull(timeoutMillis) {
            while (process.isAlive) delay(PROCESS_POLL_MILLIS)
            currentCoroutineContext().ensureActive()
            process.exitValue()
        }
        when {
            finished == null -> {
                log.w { "desktop command timed out" }
                false
            }

            finished != 0 -> {
                log.w { "desktop command exited with $finished" }
                false
            }

            else -> true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "desktop command failed" }
        false
    } finally {
        disposeDesktopProcess(process)
    }
}

private fun disposeDesktopProcess(process: Process) {
    val log = Log.tag("DesktopProcess")
    try {
        if (process.isAlive) {
            process.destroyForcibly()
            if (!process.waitFor(PROCESS_SHUTDOWN_MILLIS, TimeUnit.MILLISECONDS)) {
                log.w { "desktop command termination timed out" }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "desktop command termination failed" }
    } finally {
        listOf(process.outputStream, process.inputStream, process.errorStream).forEach(::closeProcessStream)
    }
}

private fun closeProcessStream(stream: java.io.Closeable) {
    try {
        stream.close()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.tag("DesktopProcess").w(e) { "desktop command stream close failed" }
    }
}

private const val PROCESS_POLL_MILLIS = 25L
private const val PROCESS_SHUTDOWN_MILLIS = 1000L
