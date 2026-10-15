package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.LocalAwtWindow
import com.sun.jna.Native
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.swing.SkiaSwingLayer
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.awt.Window
import javax.swing.SwingUtilities

private val documentFocusLog = Log.tag("DS/DocumentFocus")

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun rememberHbDocumentWindowFocus(): () -> Unit {
    val window = LocalAwtWindow.current
    return remember(window) {
        if (window != null && System.getProperty("os.name").orEmpty().startsWith("Linux", ignoreCase = true)) {
            { restoreLinuxDocumentFocus(window) }
        } else {
            {}
        }
    }
}

/**
 * An already focused Compose target may skip its AWT focus request, leaving JCEF's Canvas remembered.
 * Select the unique visible renderer through its public focus API after Compose accepts the target.
 * requestFocusInWindow remembers that peer even while native input leaves AWT without a focused window;
 * a current WM_TAKE_FOCUS then completes the handoff. Owned popup windows are outside this component tree.
 * Never guess between multiple renderers or replace their content target by focusing the Window itself.
 * Native ownership is checked atomically with activation; another focused AWT window takes priority.
 */
@HighFrequency
private fun restoreLinuxDocumentFocus(window: Window) {
    check(SwingUtilities.isEventDispatchThread())
    if (!window.isShowing || !window.isDisplayable || !window.isFocusableWindow) return
    val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    if (focusManager.focusedWindow?.let { it !== window } == true) return
    try {
        val target = documentFocusTarget(window) { it is SkiaLayer || it is SkiaSwingLayer }
        if (target != null) {
            val isRequested = target.requestFocusInWindow()
            documentFocusLog.v { "Linux document renderer focus requested: $isRequested" }
            if (focusManager.focusedWindow == null) {
                val isSent = requestX11DocumentFocus(Native.getWindowID(window))
                documentFocusLog.v { "Linux document focus handoff requested: $isSent" }
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        documentFocusLog.w(error) { "Linux document focus handoff failed" }
    } catch (error: LinkageError) {
        documentFocusLog.w(error) { "Linux document focus native library is unavailable" }
    }
}

/** Selects only an unambiguous live renderer, including nested scenes; inaccessible branches cannot receive input. */
internal fun documentFocusTarget(root: Container, isRenderer: (Component) -> Boolean): Component? =
    if (root.canReceiveDocumentFocus()) root.documentFocusTargets(isRenderer).singleOrNull() else null

private fun Container.documentFocusTargets(isRenderer: (Component) -> Boolean): Sequence<Component> =
    components.asSequence().filter { it.canReceiveDocumentFocus() }.flatMap { child ->
        sequence {
            if (isRenderer(child) && child.isFocusable) yield(child)
            if (child is Container) yieldAll(child.documentFocusTargets(isRenderer))
        }
    }

private fun Component.canReceiveDocumentFocus(): Boolean = isShowing && isDisplayable && isEnabled
