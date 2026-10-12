package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.io.File
import javax.swing.SwingUtilities

/** Records only test-owned component state and native ownership booleans, never other window data. */
internal class NativeBrowserFocusTrace(private val window: ComposeWindow) : AutoCloseable {
    private val output = File("build/native-ui/compose-focus-events.txt")
    private val started = System.nanoTime()
    private val listener = AWTEventListener { event ->
        val component = event.source as? Component
        if (component === window || component?.let(SwingUtilities::getWindowAncestor) === window) {
            record("event-${event.id}-${component?.javaClass?.simpleName}")
        }
    }

    init {
        check(output.parentFile.mkdirs() || output.parentFile.isDirectory)
        output.writeText("")
        Toolkit.getDefaultToolkit().addAWTEventListener(
            listener,
            AWTEvent.WINDOW_FOCUS_EVENT_MASK or AWTEvent.FOCUS_EVENT_MASK or AWTEvent.HIERARCHY_EVENT_MASK,
        )
    }

    fun record(phase: String) = nativeEdt {
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val active = focus.activeWindow
        val ownFocus = focus.focusOwner?.takeIf { SwingUtilities.getWindowAncestor(it) === window }
        val state = "${(System.nanoTime() - started) / 1_000_000}ms $phase " +
            "showing=${window.isShowing} active=${window.isActive} focused=${window.isFocused} " +
            "activeIsTest=${active === window} focusOwner=${ownFocus?.javaClass?.simpleName} " +
            nativeOwnership()
        output.appendText("$state\n")
    }

    fun tree(phase: String, component: Component) = nativeEdt {
        record(phase)
        fun visit(current: Component, depth: Int) {
            output.appendText(
                "  ${" ".repeat(depth)}${current.javaClass.simpleName} showing=${current.isShowing} " +
                    "displayable=${current.isDisplayable} visible=${current.isVisible} " +
                    "focus=${current.isFocusOwner} bounds=${current.bounds}\n",
            )
            if (current is Container) current.components.forEach { visit(it, depth + 1) }
        }
        visit(component, 0)
    }

    private fun nativeOwnership(): String {
        if (!Platform.isWindows() || !window.isDisplayable) return "nativeOwnership=unavailable"
        val users = User32.INSTANCE
        val foreground = users.GetForegroundWindow() ?: return "nativeForeground=false"
        val process = IntByReference()
        users.GetWindowThreadProcessId(foreground, process)
        val own = HWND(Native.getWindowPointer(window))
        val ancestor = users.GetAncestor(foreground, 2)
        return "nativeOwnProcess=${process.value.toLong() == ProcessHandle.current().pid()} " +
            "nativeIsTest=${foreground == own} nativeRootIsTest=${ancestor == own}"
    }

    override fun close() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
    }
}
