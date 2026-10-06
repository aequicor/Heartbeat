package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserSurface
import io.aequicor.heartbeat.feature.browser.impl.presentation.createDesktopPanel

@Composable
internal actual fun BrowserWebView(surface: BrowserSurface, modifier: Modifier) {
    key(surface) {
        val panel = remember(surface) { surface.createDesktopPanel() }
        DisposableEffect(panel) {
            onDispose { panel.release() }
        }
        SwingPanel(
            factory = panel::createComponent,
            modifier = modifier,
        )
    }
}
