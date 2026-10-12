package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ComponentEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JWindow
import javax.swing.SwingUtilities

private val log = Log.tag("DS/PopupRendering")

/**
 * Keeps Compose WINDOW-layer popups of [window] visible on macOS Metal.
 *
 * A layer window opens at the owner's size and then shrinks to its popup. Skiko 0.150.1
 * `MetalRedrawer.syncBounds` derives the native layer origin from the JRootPane height, which
 * still holds the old size during that resize, so a small popup is drawn below its own window.
 * After the root pane is laid out, this re-runs the Skia layout once so the bounds are synced again.
 * Only showing popups owned by [window] are touched; other renderers need no repair.
 */
@Composable
public fun HbDesktopPopupRendering(window: Window) {
    DisposableEffect(window) {
        val repair = HbPopupBoundsRepair(window).apply { install() }
        onDispose { repair.close() }
    }
}

internal class HbPopupBoundsRepair(private val owner: Window) : AWTEventListener {
    private val isClosed = AtomicBoolean(false)
    private val pending = mutableSetOf<Window>()

    fun install() {
        Toolkit.getDefaultToolkit().addAWTEventListener(this, AWTEvent.COMPONENT_EVENT_MASK)
        log.d { "popup bounds repair installed" }
    }

    fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        Toolkit.getDefaultToolkit().removeAWTEventListener(this)
        pending.clear()
    }

    @HighFrequency
    override fun eventDispatched(event: AWTEvent) {
        if (event.id != ComponentEvent.COMPONENT_RESIZED && event.id != ComponentEvent.COMPONENT_SHOWN) return
        val popup = event.source as? JWindow ?: return
        if (!popup.isOwnedPopupOf(owner)) return
        // Several resizes of one layer in a frame share a single deferred repair.
        if (!pending.add(popup)) return
        SwingUtilities.invokeLater { repair(popup) }
    }

    @HighFrequency
    private fun repair(popup: Window) {
        pending.remove(popup)
        if (isClosed.get() || !popup.isDisplayable || !popup.isShowing) return
        popup.validate()
        popup.skiaLayers().filter { it.renderApi == GraphicsApi.METAL }.forEach { layer ->
            layer.doLayout()
            layer.needRedraw()
        }
        log.v { "popup layer bounds synced: ${popup.bounds}" }
    }
}

internal fun Window.isOwnedPopupOf(owner: Window): Boolean =
    type == Window.Type.POPUP && generateSequence(this.owner) { it.owner }.any { it === owner }

private fun Component.skiaLayers(): Sequence<SkiaLayer> = when (this) {
    is SkiaLayer -> sequenceOf(this)
    is Container -> components.asSequence().flatMap { it.skiaLayers() }
    else -> emptySequence()
}
