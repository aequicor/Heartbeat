package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import io.aequicor.heartbeat.ds.components.HbNativeModalOwner
import io.aequicor.heartbeat.ds.components.HbNativeModalVisibility
import io.aequicor.heartbeat.ds.components.LocalHbNativeModalOwner
import io.aequicor.heartbeat.ds.theme.HbTheme
import javax.swing.JComponent

/** Native peers follow the window's modal owner while their retained browser and DOM stay alive. */
@Composable
internal fun BrowserNativeView(
    factory: () -> JComponent,
    modifier: Modifier = Modifier,
    background: Color = HbTheme.colors.surface,
) {
    val owner = LocalHbNativeModalOwner.current
    val lease = remember(factory) { NativeViewLease(factory, owner) }
    SideEffect { lease.updateOwner(owner) }
    SwingPanel(
        background = background,
        factory = lease::createComponent,
        modifier = modifier.nativeViewBacking(background),
    )
}

/**
 * A windowed browser draws its own native view above the Compose canvas, so interop's Clear only exposes the
 * window's unpainted backing beneath it. That backing shows as black wherever the peer lags behind Compose:
 * a growing host before the native view follows its new bounds, or a closing host whose peer hides before
 * Compose presents the frame without it. Kept [background] fills those gaps and the area of a peer hidden by
 * another modal, and a rounded native mask reveals the real Compose backdrop at its corners. Layout, the
 * native peer and the retained DOM are unaffected.
 */
internal fun Modifier.nativeViewBacking(background: Color): Modifier =
    background(background).graphicsLayer { alpha = 0f }

private class NativeViewLease(private val factory: () -> JComponent, private var owner: HbNativeModalOwner?) :
    RememberObserver {
    private var visibility: HbNativeModalVisibility? = null

    fun createComponent(): JComponent {
        val component = factory()
        visibility?.detach()
        val next = HbNativeModalVisibility(component)
        next.updateOwner(owner)
        next.attach()
        visibility = next
        return component
    }

    fun updateOwner(value: HbNativeModalOwner?) {
        owner = value
        visibility?.updateOwner(value)
    }

    override fun onRemembered() = Unit

    override fun onForgotten() = close()

    override fun onAbandoned() = close()

    private fun close() {
        visibility?.detach()
        visibility = null
    }
}
