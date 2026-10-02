package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseBlocker
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapabilities
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePermission
import io.aequicor.heartbeat.feature.computeruse.api.MonitorId
import io.aequicor.heartbeat.feature.computeruse.api.MonitorInfo
import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import io.aequicor.heartbeat.feature.computeruse.api.WindowId
import io.aequicor.heartbeat.feature.computeruse.api.WindowTarget
import io.aequicor.heartbeat.feature.computeruse.impl.domain.OsPermissions
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PixelGrid
import io.aequicor.heartbeat.feature.computeruse.impl.domain.RawFrame
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenCapturer
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenPoint
import io.aequicor.heartbeat.feature.computeruse.impl.domain.WindowCatalog
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import kotlin.coroutines.cancellation.CancellationException

/** Enumerates and activates windows through the platform backend of this desktop host. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class DesktopWindowCatalog(private val platform: PlatformInfo, private val dispatchers: DispatcherProvider) :
    WindowCatalog {
    private val log = Log.tag("DesktopWindowCatalog")

    override val isAvailable: Boolean
        get() = when (platform.host) {
            HostPlatform.Windows -> WindowsScreenBackend.isAvailable
            HostPlatform.MacOs -> MacOsScreenBackend.isAvailable
            HostPlatform.Linux, HostPlatform.Android, HostPlatform.Ios -> false
        }

    override suspend fun list(): List<WindowTarget> = withContext(dispatchers.io) {
        when (platform.host) {
            HostPlatform.Windows -> guarded("windows") { WindowsScreenBackend.windows() }.orEmpty()
            HostPlatform.MacOs -> guarded("windows") { MacOsScreenBackend.windows() }.orEmpty()
            HostPlatform.Linux, HostPlatform.Android, HostPlatform.Ios -> emptyList()
        }
    }

    override suspend fun resolve(id: WindowId): WindowTarget? = list().firstOrNull { it.id == id }

    override suspend fun activate(target: WindowTarget): Boolean = withContext(dispatchers.io) {
        when (platform.host) {
            // Windows can bring any window to the front before input is injected.
            HostPlatform.Windows -> guarded("activation") { WindowsScreenBackend.activate(target.id) } ?: false

            // macOS delivers input to the frontmost window only; the host refuses instead of clicking blindly.
            HostPlatform.MacOs -> guarded("frontmost probe") { MacOsScreenBackend.isFrontmost(target.id) } ?: false

            HostPlatform.Linux, HostPlatform.Android, HostPlatform.Ios -> false
        }
    }

    private fun <T> guarded(operation: String, block: () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "$operation failed" }
        null
    }
}

/**
 * Captures the desktop with AWT and a single window with the platform backend.
 *
 * A window the backend cannot render — minimized, protected or refused — falls back to a crop of the desktop,
 * which is correct exactly while nothing covers it; the frame is then what the user would have seen.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class DesktopScreenCapturer(
    private val platform: PlatformInfo,
    private val windows: WindowCatalog,
    private val dispatchers: DispatcherProvider,
) : ScreenCapturer {
    private val log = Log.tag("DesktopScreenCapturer")

    override suspend fun currentBounds(mode: ComputerUseMode): ScreenBounds? = withContext(dispatchers.io) {
        when (mode) {
            is ComputerUseMode.Desktop -> monitorBounds(mode.monitor)
            is ComputerUseMode.Window -> windows.resolve(mode.target.id)?.bounds
        }
    }

    override suspend fun capture(mode: ComputerUseMode, region: CaptureRegion?, isCursorIncluded: Boolean): RawFrame? =
        withContext(dispatchers.io) {
            val bounds = currentBounds(mode) ?: return@withContext null
            val pixels = when (mode) {
                is ComputerUseMode.Desktop -> captureScreen(bounds)
                is ComputerUseMode.Window -> captureWindow(mode, bounds)
            } ?: return@withContext null
            val isPointerMarked = isCursorIncluded && (mode !is ComputerUseMode.Desktop || mode.isCursorIncluded)
            val marked = if (isPointerMarked) {
                val pointer = guarded("pointer position") { MouseInfo.getPointerInfo()?.location }
                pointerMarker(pixels, bounds, pointer?.let { ScreenPoint(it.x, it.y) })
            } else {
                pixels
            }
            val cropped = region?.let { crop(marked, it) } ?: marked
            RawFrame(cropped, bounds, System.nanoTime())
        }

    private fun captureScreen(bounds: ScreenBounds): PixelGrid? {
        val robot = robot() ?: return null
        val image = try {
            nativeResolutionImage(
                robot.createMultiResolutionScreenCapture(
                    Rectangle(bounds.x, bounds.y, bounds.widthPx, bounds.heightPx),
                ).resolutionVariants,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "desktop capture failed area=${bounds.widthPx}x${bounds.heightPx}" }
            null
        }
        return image?.toGrid()
    }

    private suspend fun captureWindow(mode: ComputerUseMode.Window, bounds: ScreenBounds): PixelGrid? {
        val native = when (platform.host) {
            HostPlatform.Windows -> guarded("window capture") { WindowsScreenBackend.capture(mode.target.id) }
            HostPlatform.MacOs -> guarded("window capture") { MacOsScreenBackend.capture(mode.target.id) }
            HostPlatform.Linux, HostPlatform.Android, HostPlatform.Ios -> null
        }
        if (native != null) return native
        log.w { "window capture falls back to a desktop crop" }
        return captureScreen(bounds)
    }

    private fun crop(pixels: PixelGrid, region: CaptureRegion): PixelGrid {
        val inside = CaptureRegion(0, 0, pixels.widthPx, pixels.heightPx)
        val clipped = region.intersect(inside) ?: return pixels
        return pixels.region(clipped)
    }

    private fun robot(): Robot? = try {
        if (GraphicsEnvironment.isHeadless()) {
            null
        } else {
            Robot()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "input and capture device unavailable" }
        null
    }

    private fun monitorBounds(monitor: MonitorId?): ScreenBounds? {
        if (GraphicsEnvironment.isHeadless()) return null
        val devices = try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "monitor list unavailable" }
            return null
        }
        if (devices.isEmpty()) return null
        val rectangles = devices.map { it.defaultConfiguration.bounds }
        val selected = monitor?.let { id ->
            devices.firstOrNull { MonitorId(it.getIDstring()) == id }?.defaultConfiguration?.bounds
        }
        if (monitor != null && selected == null) return null
        val area = selected ?: rectangles.reduce { acc, rect -> acc.union(rect) }
        return ScreenBounds(area.x, area.y, area.width.coerceAtLeast(1), area.height.coerceAtLeast(1))
    }

    private suspend fun <T> guarded(operation: String, block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "$operation failed" }
        null
    }
}

/** Probes what this desktop host may do and opens the matching system settings page. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class DesktopOsPermissions(
    private val platform: PlatformInfo,
    private val windows: WindowCatalog,
    private val dispatchers: DispatcherProvider,
) : OsPermissions {
    private val log = Log.tag("DesktopOsPermissions")

    override suspend fun probe(): ComputerUseCapabilities = withContext(dispatchers.io) {
        val runtime = HostRuntime(platform.host, GraphicsEnvironment.isHeadless())
        val isScreenRecordingAllowed = runtime.isUsable && (!runtime.isMacOs || MacOsScreenBackend.hasScreenRecording())
        val isAccessibilityAllowed = runtime.isUsable && (!runtime.isMacOs || MacOsScreenBackend.hasAccessibility())
        val isCaptureAvailable = runtime.isUsable && isScreenRecordingAllowed
        val isInputAvailable = runtime.isUsable && isAccessibilityAllowed
        ComputerUseCapabilities(
            isCaptureAvailable = isCaptureAvailable,
            isWindowCaptureAvailable = isCaptureAvailable && windows.isAvailable,
            isInputAvailable = isInputAvailable,
            isDesktopInputAllowed = isInputAvailable,
            monitors = if (runtime.isUsable) monitors() else emptyList(),
            blockers = blockers(runtime, isScreenRecordingAllowed, isAccessibilityAllowed),
        )
    }

    /** What the user has to change before capture or input can work. */
    private fun blockers(
        runtime: HostRuntime,
        isScreenRecordingAllowed: Boolean,
        isAccessibilityAllowed: Boolean,
    ): List<ComputerUseBlocker> = buildList {
        if (!runtime.isSupported) add(ComputerUseBlocker.UnsupportedPlatform)
        if (runtime.isHeadless) add(ComputerUseBlocker.Headless)
        if (runtime.isSupported && !isScreenRecordingAllowed) add(ComputerUseBlocker.ScreenRecordingPermission)
        if (runtime.isSupported && !isAccessibilityAllowed) add(ComputerUseBlocker.AccessibilityPermission)
    }

    override suspend fun openSettings(permission: ComputerUsePermission): Boolean = withContext(dispatchers.io) {
        if (!isMacOs(platform.host)) {
            log.i { "no system settings page for permission=$permission" }
            return@withContext false
        }
        val target = when (permission) {
            ComputerUsePermission.ScreenRecording -> MAC_OS_SCREEN_CAPTURE_SETTINGS
            ComputerUsePermission.Accessibility -> MAC_OS_ACCESSIBILITY_SETTINGS
        }
        try {
            runDesktopProcess(listOf(SYSTEM_OPEN_COMMAND, target), SETTINGS_TIMEOUT_SECONDS * MILLIS_PER_SECOND).also {
                log.i { "system settings opened=$it for permission=$permission" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "system settings could not be opened" }
            false
        }
    }

    private fun monitors(): List<MonitorInfo> {
        val devices = try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.toList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "monitor list unavailable" }
            return emptyList()
        }
        val primary = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        return devices.map { device ->
            val area = device.defaultConfiguration.bounds
            MonitorInfo(
                id = MonitorId(device.getIDstring()),
                bounds = ScreenBounds(area.x, area.y, area.width.coerceAtLeast(1), area.height.coerceAtLeast(1)),
                isPrimary = device == primary,
            )
        }
    }

    private fun isMacOs(host: HostPlatform): Boolean = host == HostPlatform.MacOs

    /** Platform support and headless state, resolved once per probe. */
    private class HostRuntime(host: HostPlatform, val isHeadless: Boolean) {
        val isMacOs: Boolean = host == HostPlatform.MacOs
        val isSupported: Boolean = host == HostPlatform.Windows || isMacOs
        val isUsable: Boolean = isSupported && !isHeadless
    }

    private companion object {
        const val MAC_OS_SCREEN_CAPTURE_SETTINGS =
            "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture"
        const val MAC_OS_ACCESSIBILITY_SETTINGS =
            "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"
        const val SYSTEM_OPEN_COMMAND = "open"
        const val SETTINGS_TIMEOUT_SECONDS = 5L
        const val MILLIS_PER_SECOND = 1000L
    }
}

/** Copies a captured image into a pixel grid. */
internal fun BufferedImage.toGrid(): PixelGrid {
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            pixels[y * width + x] = getRGB(x, y)
        }
    }
    return PixelGrid(width, height, pixels)
}

/** Chooses the native device variant instead of the downscaled AWT user-space image. */
internal fun nativeResolutionImage(variants: List<Image>): BufferedImage? = variants.filterIsInstance<BufferedImage>()
    .maxByOrNull { it.width.toLong() * it.height }

/**
 * Marks the pointer location with the host's black/white crosshair, not the application's custom cursor shape.
 * Bounds and pointer are OS logical coordinates; the marker is mapped into the captured native pixel grid.
 */
internal fun pointerMarker(pixels: PixelGrid, bounds: ScreenBounds, pointer: ScreenPoint?): PixelGrid {
    if (pointer == null || pointer.x !in bounds.x until bounds.right || pointer.y !in bounds.y until bounds.bottom) {
        return pixels
    }
    val x = ((pointer.x - bounds.x).toDouble() * pixels.widthPx / bounds.widthPx).toInt()
    val y = ((pointer.y - bounds.y).toDouble() * pixels.heightPx / bounds.heightPx).toInt()
    val marked = pixels.argb.copyOf()
    drawPointerMarker(marked, pixels.widthPx, pixels.heightPx, x, y)
    return PixelGrid(pixels.widthPx, pixels.heightPx, marked)
}

private fun drawPointerMarker(marked: IntArray, width: Int, height: Int, x: Int, y: Int) {
    for (dy in -POINTER_RADIUS_PX..POINTER_RADIUS_PX) {
        for (dx in -POINTER_RADIUS_PX..POINTER_RADIUS_PX) {
            val targetX = x + dx
            val targetY = y + dy
            if (targetX !in 0 until width || targetY !in 0 until height) continue
            val isOutline = kotlin.math.abs(dx) <= POINTER_OUTLINE_PX || kotlin.math.abs(dy) <= POINTER_OUTLINE_PX
            if (isOutline) marked[targetY * width + targetX] = POINTER_BLACK
            if (dx == 0 || dy == 0) marked[targetY * width + targetX] = POINTER_WHITE
        }
    }
}

private const val POINTER_RADIUS_PX = 7
private const val POINTER_OUTLINE_PX = 1
private const val POINTER_BLACK = -0x1000000
private const val POINTER_WHITE = -1
