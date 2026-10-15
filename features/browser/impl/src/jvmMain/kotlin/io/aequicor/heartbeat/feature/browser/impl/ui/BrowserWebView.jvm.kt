package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserSurface
import io.aequicor.heartbeat.feature.browser.impl.presentation.createDesktopPanel

@Composable
internal actual fun BrowserWebView(surface: BrowserSurface, modifier: Modifier) {
    key(surface) {
        val panel = remember(surface) { surface.createDesktopPanel() }
        BrowserNativeView(
            factory = remember(panel) { panel::createComponent },
            modifier = modifier,
        )
    }
}
