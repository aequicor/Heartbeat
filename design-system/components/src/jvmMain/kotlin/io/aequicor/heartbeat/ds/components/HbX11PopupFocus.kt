package io.aequicor.heartbeat.ds.components

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.platform.unix.X11
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * Requests a WM-mediated handoff only from a native descendant of this popup's known AWT owner chain.
 * The WM must still identify the root owner as active. A nested native document may belong to an
 * ancestor JWindow, whose native X parent is the screen root rather than its AWT owner; siblings and
 * the new popup itself never authorize activation. Owner IDs are captured on EDT before this call.
 * The private connection, timestamp window, and brief server grab all end before this call returns.
 * This never sets input focus or modifies another client's window properties. Unsupported WM state
 * fails closed; native failures propagate to the popup listener's warning log.
 */
internal fun requestX11PopupFocus(owner: Long, popup: Long, focusOwners: Set<Long>): Boolean {
    if (!areDistinctX11Windows(owner, popup) || !areValidFocusOwners(owner, popup, focusOwners)) return false
    return HbX11PopupConnection().use { it.request(owner, popup, focusOwners) }
}

private class HbX11PopupConnection : AutoCloseable {
    private val x11 = X11.INSTANCE
    private val library = NativeLibrary.getInstance("X11")
    private val display = checkNotNull(x11.XOpenDisplay(null)) { "Cannot open the popup X11 display" }

    fun request(owner: Long, popup: Long, focusOwners: Set<Long>): Boolean {
        val root = x11.XDefaultRootWindow(display)
        val activeAtom = atom("_NET_ACTIVE_WINDOW", onlyExisting = true) ?: return false
        val timestamp = serverTimestamp(root)
        library.getFunction("XGrabServer").invokeInt(arrayOf(display))
        return try {
            // No AWT calls, logging, polling, or other display connections while the server is grabbed.
            val active = activeWindow(root, activeAtom)
            val focus = X11.WindowByReference()
            library.getFunction("XGetInputFocus").invokeInt(arrayOf(display, focus, IntByReference()))
            val ancestors = x11FocusAncestors(focus.value?.toLong() ?: 0L, ::parentWindow)
            val areWindowsMapped = isMapped(owner) && isMapped(popup)
            if (canRestoreX11PopupFocus(owner, popup, active, ancestors, areWindowsMapped, focusOwners)) {
                sendActivation(root, activeAtom, owner, timestamp)
                true
            } else {
                false
            }
        } finally {
            library.getFunction("XUngrabServer").invokeInt(arrayOf(display))
            x11.XFlush(display)
        }
    }

    override fun close() {
        x11.XCloseDisplay(display)
    }

    /**
     * PropertyNotify supplies server time without generating keyboard or pointer input. XSync queues
     * events from all preceding requests before returning; the matching event can then be read once.
     * The private InputOnly window is never mapped. No polling or blocking XNextEvent runs on EDT.
     */
    private fun serverTimestamp(root: X11.Window): Long {
        val handle = library.getFunction("XCreateWindow").invoke(
            NativeLong::class.java,
            arrayOf(display, root, 0, 0, 1, 1, 0, 0, X11.InputOnly, Pointer.NULL, NativeLong(0), Pointer.NULL),
        ) as NativeLong
        check(handle.toLong() > 1L) { "Cannot create the X11 timestamp window" }
        val window = X11.Window(handle.toLong())
        return try {
            val property = checkNotNull(atom("_HEARTBEAT_POPUP_TIMESTAMP", onlyExisting = false))
            x11.XSelectInput(display, window, NativeLong(X11.PropertyChangeMask.toLong()))
            Memory(1).use { value ->
                value.setByte(0, 1)
                x11.XChangeProperty(
                    display,
                    window,
                    property,
                    X11.XA_INTEGER,
                    BYTE_FORMAT,
                    X11.PropModeReplace,
                    value,
                    1,
                )
            }
            library.getFunction("XSync").invokeInt(arrayOf(display, 0))
            readTimestamp(window, property)
        } finally {
            x11.XDestroyWindow(display, window)
        }
    }

    private fun readTimestamp(window: X11.Window, property: X11.Atom): Long {
        val event = X11.XEvent()
        check(x11.XCheckTypedWindowEvent(display, window, X11.PropertyNotify, event)) {
            "X11 timestamp event is missing after synchronization"
        }
        event.setType(X11.XPropertyEvent::class.java)
        event.read()
        check(event.xproperty.atom == property && event.xproperty.state == X11.PropertyNewValue) {
            "X11 timestamp event does not match the private property"
        }
        val timestamp = event.xproperty.time.toLong() and UNSIGNED_X11_TIME
        check(timestamp != 0L) { "X11 timestamp is CurrentTime rather than server time" }
        return timestamp
    }

    private fun sendActivation(root: X11.Window, atom: X11.Atom, owner: Long, timestamp: Long) {
        val event = X11.XEvent()
        event.setType(X11.XClientMessageEvent::class.java)
        event.xclient.apply {
            type = X11.ClientMessage
            serial = NativeLong(0)
            send_event = 1
            display = this@HbX11PopupConnection.display
            window = X11.Window(owner)
            message_type = atom
            format = CARDINAL_FORMAT
            data.setType(Array<NativeLong>::class.java)
            data.l = arrayOf(NativeLong(1), NativeLong(timestamp), NativeLong(owner), NativeLong(0), NativeLong(0))
        }
        event.write()
        val mask = NativeLong((X11.SubstructureRedirectMask or X11.SubstructureNotifyMask).toLong())
        check(x11.XSendEvent(display, root, 0, mask, event) != 0) { "The X11 activation request could not be sent" }
    }

    private fun isMapped(window: Long): Boolean {
        val attributes = X11.XWindowAttributes()
        check(x11.XGetWindowAttributes(display, X11.Window(window), attributes) != 0) {
            "Cannot query the popup X11 window attributes"
        }
        return attributes.map_state == X11.IsViewable
    }

    private fun parentWindow(window: Long): Long {
        val root = X11.WindowByReference()
        val parent = X11.WindowByReference()
        val children = PointerByReference()
        return try {
            check(x11.XQueryTree(display, X11.Window(window), root, parent, children, IntByReference()) != 0) {
                "Cannot query the X11 focus ancestry"
            }
            parent.value?.toLong() ?: 0L
        } finally {
            children.value?.let { x11.XFree(it) }
        }
    }

    private fun activeWindow(root: X11.Window, atom: X11.Atom): Long {
        val type = X11.AtomByReference()
        val format = IntByReference()
        val count = NativeLongByReference()
        val remaining = NativeLongByReference()
        val data = PointerByReference()
        return try {
            val result = x11.XGetWindowProperty(
                display,
                root,
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
            check(result == X11.Success) { "Cannot query the WM's active X11 window" }
            val isWindow = type.value == X11.XA_WINDOW && format.value == CARDINAL_FORMAT
            val isSingleValue = count.value.toLong() == 1L && remaining.value.toLong() == 0L
            if (isWindow && isSingleValue) data.value?.getNativeLong(0)?.toLong() ?: 0L else 0L
        } finally {
            data.value?.let { x11.XFree(it) }
        }
    }

    /** XInternAtom's protocol Bool must be 0/1; JNA marshals Boolean true as -1 and causes BadValue. */
    private fun atom(name: String, onlyExisting: Boolean): X11.Atom? {
        val value = library.getFunction("XInternAtom").invoke(
            NativeLong::class.java,
            arrayOf(display, name, if (onlyExisting) 1 else 0),
        ) as NativeLong
        return value.toLong().takeIf { it != 0L }?.let { X11.Atom(it) }
    }
}

/** WM activity and native focus ancestry must independently identify this live owner's window. */
internal fun canRestoreX11PopupFocus(
    owner: Long,
    popup: Long,
    active: Long,
    ancestors: List<Long>?,
    areWindowsMapped: Boolean,
    focusOwners: Set<Long> = setOf(owner),
): Boolean {
    if (!areDistinctX11Windows(owner, popup)) return false
    val isActiveOwner = active == owner && ancestors?.any { it in focusOwners } == true
    return areValidFocusOwners(owner, popup, focusOwners) && isActiveOwner && areWindowsMapped
}

private fun areDistinctX11Windows(owner: Long, popup: Long): Boolean = owner > 1L && popup > 1L && owner != popup

private fun areValidFocusOwners(owner: Long, popup: Long, focusOwners: Set<Long>): Boolean =
    owner in focusOwners && popup !in focusOwners && focusOwners.all { it > 1L }

/** None, PointerRoot, truncated paths, and cycles cannot establish native focus ownership. */
internal fun x11FocusAncestors(focused: Long, parentOf: (Long) -> Long?): List<Long>? {
    var current = focused
    val ancestors = mutableListOf<Long>()
    while (current > 1L && current !in ancestors && ancestors.size < MAX_X11_ANCESTORS) {
        ancestors += current
        current = parentOf(current) ?: return null
    }
    return ancestors.takeIf { current == 0L && it.isNotEmpty() }
}

private const val MAX_X11_ANCESTORS = 64
private const val BYTE_FORMAT = 8
private const val CARDINAL_FORMAT = 32
private const val UNSIGNED_X11_TIME = 0xffffffffL
