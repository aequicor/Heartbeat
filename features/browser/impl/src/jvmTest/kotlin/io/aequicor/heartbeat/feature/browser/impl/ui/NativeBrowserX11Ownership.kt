package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.platform.unix.X11
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Window
import java.io.File

/**
 * Authorizes Robot only when X11 focus descends from this test's live AWT window and the WM agrees.
 * Chromium embeds a native X11 child: clicking it can clear AWT focus while DOM focus remains valid.
 * Showing owned AWT windows are explicitly enumerated; a foreign active window always fails closed.
 *
 * Called on EDT. Handles and AWT geometry are captured before a short server grab; all Xlib queries then
 * use one private display connection. The grab gives a consistent snapshot and prevents a foreign focus
 * window disappearing during XQueryTree without installing a process-global Xlib error handler. No focus,
 * stacking, or window state is changed. The bounded walk, ungrab, and display close run in this scope.
 * The test-only audit file records own, focused, active, and ancestor XIDs, without titles or content.
 */
internal fun ComposeWindow.linuxNativeForegroundOwnership(): NativeBrowserWindowOwnership {
    val own = Native.getWindowID(this)
    val owned = showingOwnedWindows().map(Native::getWindowID).toSet()
    val bounds = Rectangle(locationOnScreen, size)
    val intersectsScreen = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any {
        it.defaultConfiguration.bounds.intersects(bounds)
    }
    val x11 = X11.INSTANCE
    val display = x11.XOpenDisplay(null) ?: return x11Ownership(own, owned, null, 0, 0, false)
    val snapshot = try {
        x11.focusSnapshot(display, own)
    } finally {
        x11.XCloseDisplay(display)
    }
    return x11Ownership(
        own,
        owned,
        snapshot.ancestors,
        snapshot.focused,
        snapshot.active,
        snapshot.isMapped && intersectsScreen,
    )
}

/** Only AWT-owned peers known to this test may authorize popup input, never arbitrary WM transient windows. */
private fun Window.showingOwnedWindows(): List<Window> = ownedWindows.flatMap { child ->
    listOfNotNull(child.takeIf { it.isShowing && it.isDisplayable }) + child.showingOwnedWindows()
}

private data class X11FocusSnapshot(
    val ancestors: List<Long>?,
    val focused: Long,
    val active: Long,
    val isMapped: Boolean,
)

/** No AWT calls or file IO while the X server is grabbed; each request uses this private connection. */
private fun X11.focusSnapshot(display: X11.Display, own: Long): X11FocusSnapshot {
    val library = NativeLibrary.getInstance("X11")
    library.getFunction("XGrabServer").invokeInt(arrayOf(display))
    try {
        val focus = X11.WindowByReference()
        library.getFunction("XGetInputFocus").invokeInt(arrayOf(display, focus, IntByReference()))
        val active = activeWindow(display)
        val ancestors = focusAncestors(display, focus.value)
        val attributes = X11.XWindowAttributes()
        val isMapped = XGetWindowAttributes(display, X11.Window(own), attributes) != 0 &&
            attributes.map_state == X11.IsViewable
        return X11FocusSnapshot(ancestors, focus.value?.toLong() ?: 0, active, isMapped)
    } finally {
        library.getFunction("XUngrabServer").invokeInt(arrayOf(display))
        XFlush(display)
    }
}

private fun X11.activeWindow(display: X11.Display): Long {
    val atom = existingX11Atom(display, "_NET_ACTIVE_WINDOW") ?: return 0
    val type = X11.AtomByReference()
    val format = IntByReference()
    val count = NativeLongByReference()
    val remaining = NativeLongByReference()
    val data = PointerByReference()
    try {
        val result = XGetWindowProperty(
            display,
            XDefaultRootWindow(display),
            atom,
            NativeLong(0),
            NativeLong(1),
            false,
            X11.XA_WINDOW,
            type,
            format,
            count,
            remaining,
            data,
        )
        val isWindow = result == X11.Success && type.value == X11.XA_WINDOW && format.value == 32
        val isSingleValue = count.value.toLong() == 1L && remaining.value.toLong() == 0L
        return if (isWindow && isSingleValue) data.value?.getNativeLong(0)?.toLong() ?: 0 else 0
    } finally {
        data.value?.let { XFree(it) }
    }
}

/** JNA marshals Boolean true as -1; XInternAtom requires the protocol Bool value 1, or it raises BadValue. */
private fun existingX11Atom(display: X11.Display, name: String): X11.Atom? {
    val value = NativeLibrary.getInstance("X11").getFunction("XInternAtom").invoke(
        NativeLong::class.java,
        arrayOf(display, name, 1),
    ) as NativeLong
    return value.toLong().takeIf { it != 0L }?.let { X11.Atom(it) }
}

/** None and PointerRoot are not windows; an incomplete or cyclic ancestry cannot authorize input. */
private fun X11.focusAncestors(display: X11.Display, focused: X11.Window?): List<Long>? {
    var current = focused?.toLong() ?: 0L
    val ancestors = mutableListOf<Long>()
    while (current > 1L && current !in ancestors && ancestors.size < MAX_X11_ANCESTORS) {
        ancestors += current
        current = parentWindow(display, current) ?: return null
    }
    return ancestors.takeIf { current == 0L && it.isNotEmpty() }
}

private fun X11.parentWindow(display: X11.Display, window: Long): Long? {
    val root = X11.WindowByReference()
    val parent = X11.WindowByReference()
    val children = PointerByReference()
    return try {
        val result = XQueryTree(display, X11.Window(window), root, parent, children, IntByReference())
        if (result != 0) parent.value?.toLong() ?: 0L else null
    } finally {
        children.value?.let { XFree(it) }
    }
}

private fun x11Ownership(
    own: Long,
    owned: Set<Long>,
    ancestors: List<Long>?,
    focused: Long,
    active: Long,
    isVisible: Boolean,
): NativeBrowserWindowOwnership {
    val result = classifyX11Ownership(own, owned, ancestors, active, isVisible)
    val output = File("build/native-ui/compose-native-x11-focus.txt")
    check(output.parentFile.mkdirs() || output.parentFile.isDirectory)
    output.appendText("own=$own owned=$owned focused=$focused ancestors=$ancestors active=$active ownership=$result\n")
    return result
}

/** Focus ancestry and the WM's active client are independent requirements, including owned popup layers. */
internal fun classifyX11Ownership(
    own: Long,
    owned: Set<Long>,
    ancestors: List<Long>?,
    active: Long,
    isVisible: Boolean,
): NativeBrowserWindowOwnership {
    val focusedOwner = ancestors?.firstOrNull { it == own || it in owned }
    val hasMatchingActiveWindow = active == own || (focusedOwner in owned && active in owned)
    val belongsToTest = hasMatchingActiveWindow && focusedOwner != null
    return NativeBrowserWindowOwnership(
        isOwnProcess = belongsToTest,
        isTestRoot = belongsToTest && focusedOwner == own,
        isTestRootOwner = belongsToTest && focusedOwner in owned,
        isVisibleOnMonitor = isVisible,
    )
}

private const val MAX_X11_ANCESTORS = 64
