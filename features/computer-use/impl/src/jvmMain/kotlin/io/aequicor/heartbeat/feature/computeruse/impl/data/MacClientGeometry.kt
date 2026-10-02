package io.aequicor.heartbeat.feature.computeruse.impl.data

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PixelGrid
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/** Reads explicit AX content geometry only; missing or ambiguous metadata never becomes a guessed title inset. */
internal class MacClientGeometry(private val ax: ApplicationServicesLib, private val cf: CoreFoundationLib) {
    private val log = Log.tag("MacClientGeometry")

    fun bounds(pid: Int, window: ScreenBounds): ScreenBounds? {
        if (pid <= 0 || !ax.AXIsProcessTrusted()) return null
        return try {
            val app = ax.AXUIElementCreateApplication(pid) ?: return null
            try {
                attribute(app, "AXWindows") { windows ->
                    val matches = elements(windows).filter { rectangle(it) == window }
                    val match = matches.singleOrNull() ?: return@attribute null
                    attribute(match, "AXContents") { contents ->
                        val content = elements(contents).singleOrNull() ?: return@attribute null
                        rectangle(content)?.takeIf { isClientRectangle(window, it) }
                    }
                }
            } finally {
                cf.CFRelease(app)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "AX client geometry unavailable pid=$pid" }
            null
        }
    }

    private fun rectangle(element: Pointer): ScreenBounds? {
        val position = attribute(element, "AXPosition") { pair(it, POINT_TYPE) } ?: return null
        val size = attribute(element, "AXSize") { pair(it, SIZE_TYPE) } ?: return null
        if (!listOf(position.first, position.second, size.first, size.second).all(Double::isFinite)) return null
        val width = size.first.roundToInt()
        val height = size.second.roundToInt()
        return if (width > 0 && height > 0) {
            ScreenBounds(position.first.roundToInt(), position.second.roundToInt(), width, height)
        } else {
            null
        }
    }

    private fun pair(value: Pointer, type: Int): Pair<Double, Double>? {
        if (ax.AXValueGetType(value) != type) return null
        return Memory(PAIR_BYTES).use { memory ->
            if (!ax.AXValueGetValue(value, type, memory)) {
                null
            } else {
                memory.getDouble(0) to memory.getDouble(DOUBLE_BYTES)
            }
        }
    }

    private fun elements(array: Pointer): List<Pointer> {
        if (cf.CFGetTypeID(array) != cf.CFArrayGetTypeID()) return emptyList()
        val count = cf.CFArrayGetCount(array)
        if (count !in 1..MAX_ELEMENTS) return emptyList()
        return (0 until count).mapNotNull { cf.CFArrayGetValueAtIndex(array, it) }
    }

    private fun <T> attribute(element: Pointer, name: String, read: (Pointer) -> T?): T? {
        val timeoutResult = ax.AXUIElementSetMessagingTimeout(element, AX_TIMEOUT_SECONDS)
        check(timeoutResult == 0) { "AX messaging timeout unavailable nativeError=$timeoutResult" }
        val key = cf.CFStringCreateWithCString(null, name, UTF8_ENCODING) ?: return null
        try {
            val result = PointerByReference()
            val code = ax.AXUIElementCopyAttributeValue(element, key, result)
            return if (code != 0) {
                log.d { "AX attribute unavailable attribute=$name nativeError=$code" }
                null
            } else {
                result.value?.let { value ->
                    try {
                        read(value)
                    } finally {
                        cf.CFRelease(value)
                    }
                }
            }
        } finally {
            cf.CFRelease(key)
        }
    }

    private companion object {
        const val AX_TIMEOUT_SECONDS = 0.1f
        const val POINT_TYPE = 1
        const val SIZE_TYPE = 2
        const val DOUBLE_BYTES = 8L
        const val PAIR_BYTES = 16L
        const val UTF8_ENCODING = 0x08000100
        const val MAX_ELEMENTS = 4096L
    }
}

/** Only an explicit, full-width content container ending at the window bottom identifies its client area. */
internal fun isClientRectangle(window: ScreenBounds, client: ScreenBounds): Boolean =
    client.x == window.x && client.right == window.right && client.bottom == window.bottom && client.y >= window.y

/** Converts AX screen points to the actual captured raster, including Retina scale and negative screen origins. */
internal fun cropClientPixels(pixels: PixelGrid, window: ScreenBounds, client: ScreenBounds): PixelGrid? {
    if (!isClientRectangle(window, client)) return null
    val x = ((client.x - window.x).toDouble() * pixels.widthPx / window.widthPx).roundToInt()
    val y = ((client.y - window.y).toDouble() * pixels.heightPx / window.heightPx).roundToInt()
    val right = ((client.right - window.x).toDouble() * pixels.widthPx / window.widthPx).roundToInt()
    val bottom = ((client.bottom - window.y).toDouble() * pixels.heightPx / window.heightPx).roundToInt()
    if (right <= x || bottom <= y) return null
    return pixels.region(CaptureRegion(x, y, right - x, bottom - y))
}
