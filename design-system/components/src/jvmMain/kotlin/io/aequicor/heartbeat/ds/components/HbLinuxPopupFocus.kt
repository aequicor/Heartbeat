package io.aequicor.heartbeat.ds.components

import com.sun.jna.Native
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import java.awt.AWTEvent
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ComponentEvent
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JWindow
import javax.swing.SwingUtilities

/**
 * Restores the normal AWT popup handoff after an embedded X11 child receives native input.
 * JBR's XBaseWindow.globalUserTime misses input handled by another X connection; its next popup
 * focus request can therefore predate the server's last focus change and be ignored. A guarded
 * EWMH activation request produces a real WM_TAKE_FOCUS with a current timestamp. JBR then focuses
 * the popup it already remembered while showing it, including Compose's normal content target.
 *
 * Never request focus on the JWindow itself: that replaces the most recent content focus owner
 * with the window and consumes menu keys before they reach Compose. This listener belongs to one
 * root window, handles each deferred showing once, and removes all work when its scope closes.
 */
internal class HbLinuxPopupFocus(private val owner: Window) : AWTEventListener {
    private val log = Log.tag("DS/LinuxPopupFocus")
    private val isClosed = AtomicBoolean(false)
    private val requests = HbPopupFocusRequests<Window>({ SwingUtilities.invokeLater(it) }, ::restoreFocus)

    fun install() {
        Toolkit.getDefaultToolkit().addAWTEventListener(
            this,
            AWTEvent.COMPONENT_EVENT_MASK or AWTEvent.WINDOW_EVENT_MASK,
        )
        log.d { "Linux popup focus handoff installed" }
    }

    fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        Toolkit.getDefaultToolkit().removeAWTEventListener(this)
        if (SwingUtilities.isEventDispatchThread()) requests.close() else SwingUtilities.invokeLater(requests::close)
    }

    @HighFrequency
    override fun eventDispatched(event: AWTEvent) {
        if (isClosed.get()) return
        val popup = event.source as? JWindow ?: return
        if (!popup.isOwnedPopupOf(owner)) return
        when (event.id) {
            ComponentEvent.COMPONENT_SHOWN -> requests.shown(popup)
            ComponentEvent.COMPONENT_HIDDEN, WindowEvent.WINDOW_CLOSED -> requests.hidden(popup)
        }
    }

    @HighFrequency
    private fun restoreFocus(popup: Window) {
        if (isClosed.get() || !popup.canRestorePopupFocus(owner)) return
        if (KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow != null) return
        try {
            // Capture AWT peer IDs on EDT, before the private X connection grabs the server.
            val nativeOwners = (generateSequence(popup.owner) { it.owner }.takeWhile { it !== owner } + owner)
                .filter { it.isShowing && it.isDisplayable }
                .map(Native::getWindowID)
                .toSet()
            val sent = requestX11PopupFocus(Native.getWindowID(owner), Native.getWindowID(popup), nativeOwners)
            log.v { "Linux popup focus handoff requested: $sent" }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Linux popup focus handoff failed" }
        } catch (error: LinkageError) {
            log.w(error) { "Linux popup focus native library is unavailable" }
        }
    }
}

private fun Window.canRestorePopupFocus(owner: Window): Boolean {
    val isEligible = isShowing && isFocusableWindow && isAutoRequestFocus
    val isOwnerVisible = owner.isShowing && owner.isDisplayable
    return isEligible && isOwnerVisible && isOwnedPopupOf(owner)
}

/** EDT-confined queue: an obsolete showing cannot consume a newer showing's pending handoff. */
internal class HbPopupFocusRequests<T : Any>(
    private val defer: (() -> Unit) -> Unit,
    private val request: (T) -> Unit,
) {
    private val pending = mutableMapOf<T, Any>()
    private var isClosed = false

    fun shown(popup: T) {
        if (isClosed || popup in pending) return
        val token = Any()
        pending[popup] = token
        defer {
            if (!isClosed && pending[popup] === token) {
                pending.remove(popup)
                request(popup)
            }
        }
    }

    fun hidden(popup: T) {
        pending.remove(popup)
    }

    fun close() {
        isClosed = true
        pending.clear()
    }
}
