package io.aequicor.heartbeat.feature.browser.impl.ui

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
import io.aequicor.heartbeat.ds.components.LocalHbActiveNativeModalOwner
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
    val active = LocalHbActiveNativeModalOwner.current
    val lease = remember(factory) { NativeViewLease(factory, owner) }
    SideEffect { lease.updateOwner(owner) }
    SwingPanel(
        background = background,
        factory = lease::createComponent,
        modifier = modifier.nativeViewDrawing(active == null || active === owner),
    )
}

/** Hides interop's Clear draw along with its native peer, without changing layout or the retained DOM. */
internal fun Modifier.nativeViewDrawing(isVisible: Boolean): Modifier = graphicsLayer {
    alpha = if (isVisible) 1f else 0f
}

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
