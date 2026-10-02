package io.aequicor.heartbeat.platform.desktop

import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Window
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbSpacing
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapturePresentation
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePermission
import io.aequicor.heartbeat.platform.shared.ComputerUsePermissionGuidePanel
import io.aequicor.heartbeat.platform.shared.computerUsePermissionGuideTitle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.awt.Window as AwtWindow

/**
 * Floating macOS panel of a permission grant, attached to the System Settings window: below it, or beside it when
 * the screen has no room. It is a non-activating `NSPanel`, so dragging its tile into the list keeps System Settings
 * in front instead of activating Heartbeat. The panel hides while System Settings is closed or minimized; a System
 * Settings window that never appears leaves it at the bottom of the screen.
 */
@Composable
internal fun ComputerUsePermissionGuideWindow(
    permission: ComputerUsePermission,
    io: CoroutineDispatcher,
    capturePresentation: ComputerUseCapturePresentation,
    onClose: () -> Unit,
) {
    val setup by produceState<PermissionGuideSetup?>(null, io) {
        value = withContext(io) {
            permissionGuideOrNull("initialize the application tile") {
                val cocoa = MacWindowAccess()
                MacGrantTargets(cocoa).resolve()?.let { target ->
                    PermissionGuideSetup(cocoa, target, MacSettingsWindowTracker(cocoa))
                }
            }
        }
    }
    val resolved = setup ?: return
    val title = computerUsePermissionGuideTitle()
    val panel = remember { createGuideWindow(title) }
    val presentation = remember(panel) {
        val native = DesktopCapturePresentation(panel, ComputerUseScreenOverlay())
        PermissionGuidePresentation(
            native,
            native::close,
            setVisible = { isVisible ->
                if (panel.isVisible != isVisible) {
                    Log.tag("PermissionGuideWindow").d { "Permission guide visibility: $isVisible" }
                    panel.isVisible = isVisible
                }
            },
            enqueue = { action -> EventQueue.invokeLater(action) },
        )
    }
    DisposableEffect(presentation, capturePresentation) {
        val registration = permissionGuideOrNull("register capture exclusion") {
            capturePresentation.register(presentation)
        }
        if (registration == null) presentation.close()
        onDispose {
            try {
                presentation.close()
            } finally {
                registration?.close()
            }
        }
    }
    // Placement starts outside the window composition, before the panel is first shown.
    LaunchedEffect(resolved, panel, presentation, io) {
        val gap = HbSpacing().l.value.roundToInt()
        followSettingsWindow(
            bounds = { withContext(io) { resolved.tracker.bounds() } },
            place = { settings ->
                panel.location = permissionGuideLocation(settings, usableArea(settings), panel.size, gap)
            },
            onShown = presentation::place,
        )
    }
    Window(
        // AwtWindow queues setVisible on the next event tick. It must never enqueue a show outside our gate.
        // A displayable hidden window still composes and measures; presentation owns its actual visibility.
        visible = false,
        create = { panel },
        dispose = ComposeWindow::dispose,
        // A displayable window lays its content out while still hidden, so it is shown already measured.
        update = { window ->
            if (!window.isDisplayable) {
                window.pack()
                resolved.cocoa.preventActivation(window.title)
            }
        },
    ) {
        val density = LocalDensity.current
        HbTheme {
            ComputerUsePermissionGuidePanel(
                permission,
                resolved.target.name,
                resolved.target.icon,
                onClose,
                // The window follows the panel's measured size; AWT sizes windows in points, which equal dp here.
                Modifier.onSizeChanged { size ->
                    val measured = with(density) {
                        Dimension(size.width.toDp().value.roundToInt(), size.height.toDp().value.roundToInt())
                    }
                    if (window.size != measured) window.size = measured
                },
                tileModifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                    .dragAndDropSource { offset -> resolved.target.dragData(offset) },
            )
        }
    }
}

/** Tracks placement until cancellation; a native failure hides the guide and ends only this optional task. */
internal suspend fun followSettingsWindow(
    bounds: suspend () -> Rectangle?,
    place: (Rectangle?) -> Unit,
    onShown: (Boolean) -> Unit,
) {
    var hasSeenSettings = false
    var waited = 0.milliseconds
    while (true) {
        val isSuccessful = permissionGuideOrNull("follow System Settings") {
            val settings = bounds()
            hasSeenSettings = hasSeenSettings || settings != null
            val isShown = settings != null || (!hasSeenSettings && waited >= SETTINGS_WAIT)
            if (isShown) place(settings)
            onShown(isShown)
            true
        } ?: false
        if (!isSuccessful) {
            onShown(false)
            return
        }
        delay(TRACKING_INTERVAL)
        waited += TRACKING_INTERVAL
    }
}

private data class PermissionGuideSetup(
    val cocoa: MacWindowAccess,
    val target: GrantTarget,
    val tracker: MacSettingsWindowTracker,
)

/** A utility window gets an `NSPanel` peer; the type must be set before the window becomes displayable. */
private fun createGuideWindow(title: String): ComposeWindow = ComposeWindow().apply {
    type = AwtWindow.Type.UTILITY
    this.title = title
    isUndecorated = true
    isTransparent = true
    isAlwaysOnTop = true
    isResizable = false
    focusableWindowState = false
    isAutoRequestFocus = false
}

/**
 * Makes the panel non-activating: clicking or dragging its tile then leaves System Settings the active application
 * instead of activating Heartbeat and raising its windows over the list. Only an `NSPanel` honors the flag; the
 * title must be unique among the application's windows.
 */
private fun MacWindowAccess.preventActivation(title: String) {
    permissionGuideOrNull("make the panel non-activating") {
        onMainThread {
            val panel = window(title)
            send(panel, "setStyleMask:", NativeLong(number(panel, "styleMask") or NON_ACTIVATING_PANEL))
            Log.tag("PermissionGuideWindow").d { "permission guide panel is non-activating" }
        }
    }
}

/**
 * Where the panel goes for a System Settings window at [settings] on a screen whose usable [area] excludes the
 * menu bar and the Dock: centered below the window, else beside it (right first) aligned to its bottom, else over its
 * bottom edge. Without a window the panel sits at the bottom center of [area]. The result never leaves [area].
 */
internal fun permissionGuideLocation(settings: Rectangle?, area: Rectangle, panel: Dimension, gap: Int): Point {
    val wanted = if (settings == null) {
        Point(area.x + (area.width - panel.width) / 2, area.y + area.height - panel.height - gap)
    } else {
        val centered = settings.x + (settings.width - panel.width) / 2
        val alignedBottom = settings.y + settings.height - panel.height
        val below = settings.y + settings.height + gap
        val right = settings.x + settings.width + gap
        val left = settings.x - gap - panel.width
        when {
            below + panel.height <= area.y + area.height -> Point(centered, below)
            right + panel.width <= area.x + area.width -> Point(right, alignedBottom)
            left >= area.x -> Point(left, alignedBottom)
            else -> Point(centered, alignedBottom - gap)
        }
    }
    return Point(
        wanted.x.coerceIn(area.x, (area.x + area.width - panel.width).coerceAtLeast(area.x)),
        wanted.y.coerceIn(area.y, (area.y + area.height - panel.height).coerceAtLeast(area.y)),
    )
}

/** The usable part of the screen showing [settings], or of the main screen without it. */
private fun usableArea(settings: Rectangle?): Rectangle {
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val device = settings?.let { window ->
        environment.screenDevices.firstOrNull { screen ->
            screen.defaultConfiguration.bounds.contains(window.centerX, window.centerY)
        }
    } ?: environment.defaultScreenDevice
    val configuration = device.defaultConfiguration
    val area = Rectangle(configuration.bounds)
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
    area.x += insets.left
    area.y += insets.top
    area.width -= insets.left + insets.right
    area.height -= insets.top + insets.bottom
    return area
}

/**
 * Finds the System Settings window through WindowServer. Window bounds and owners are listed without the Screen
 * Recording permission (only titles need it); CoreGraphics' global coordinates match AWT's on macOS.
 */
private class MacSettingsWindowTracker(private val cocoa: MacWindowAccess) {
    private val graphics = NativeLibrary.getInstance(CORE_GRAPHICS)
    private val copyWindows = graphics.getFunction("CGWindowListCopyWindowInfo")
    private val rectangle = graphics.getFunction("CGRectMakeWithDictionaryRepresentation")
    private val release = NativeLibrary.getInstance(CORE_FOUNDATION).getFunction("CFRelease")
    private val ownerKey = graphics.getGlobalVariableAddress("kCGWindowOwnerPID").getPointer(0)
    private val layerKey = graphics.getGlobalVariableAddress("kCGWindowLayer").getPointer(0)
    private val boundsKey = graphics.getGlobalVariableAddress("kCGWindowBounds").getPointer(0)
    private val settingsProcesses = ConcurrentHashMap<Long, Boolean>()

    /** The main System Settings window, or `null` while none is on screen. */
    fun bounds(): Rectangle? {
        val windows = copyWindows.invokePointer(arrayOf<Any>(ON_SCREEN_WINDOWS, 0)) ?: return null
        return try {
            cocoa.objects(windows).mapNotNull(::settingsWindow).maxByOrNull { it.width.toLong() * it.height }
        } finally {
            release.invokeVoid(arrayOf(windows))
        }
    }

    private fun settingsWindow(window: Pointer): Rectangle? {
        val layer = cocoa.optionalPointer(window, "objectForKey:", layerKey)
        val owner = cocoa.optionalPointer(window, "objectForKey:", ownerKey)
        val bounds = cocoa.optionalPointer(window, "objectForKey:", boundsKey)
        val isSettings = layer != null && owner != null && bounds != null &&
            cocoa.number(layer, "longLongValue") == 0L && isSystemSettings(cocoa.number(owner, "longLongValue"))
        if (!isSettings) return null
        val rect = Memory(RECT_FIELDS.toLong() * Double.SIZE_BYTES)
        if (rectangle.invokeInt(arrayOf(bounds, rect)) and BOOLEAN_MASK == 0) return null
        val fields = rect.getDoubleArray(0, RECT_FIELDS).map { it.roundToInt() }
        return Rectangle(fields[RECT_X], fields[RECT_Y], fields[RECT_WIDTH], fields[RECT_HEIGHT])
    }

    /** Identified by executable path, which, unlike the owner name, is not localized. */
    private fun isSystemSettings(pid: Long): Boolean = settingsProcesses.getOrPut(pid) {
        ProcessHandle.of(pid).flatMap { it.info().command() }
            .map { command -> SETTINGS_EXECUTABLES.any(command::endsWith) }
            .orElse(false)
    }

    private companion object {
        const val CORE_GRAPHICS = "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics"
        const val CORE_FOUNDATION = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"

        // kCGWindowListOptionOnScreenOnly | kCGWindowListExcludeDesktopElements
        const val ON_SCREEN_WINDOWS = 1 or 16

        // CGRect: origin x, origin y, width, height as CGFloat (double on 64-bit macOS).
        const val RECT_FIELDS = 4
        const val RECT_X = 0
        const val RECT_Y = 1
        const val RECT_WIDTH = 2
        const val RECT_HEIGHT = 3
        const val BOOLEAN_MASK = 0xff
        val SETTINGS_EXECUTABLES = listOf(
            "/System Settings.app/Contents/MacOS/System Settings",
            "/System Preferences.app/Contents/MacOS/System Preferences",
        )
    }
}

// NSWindowStyleMaskNonactivatingPanel
private const val NON_ACTIVATING_PANEL = 1L shl 7
private val TRACKING_INTERVAL = 250.milliseconds
private val SETTINGS_WAIT = 3.seconds
