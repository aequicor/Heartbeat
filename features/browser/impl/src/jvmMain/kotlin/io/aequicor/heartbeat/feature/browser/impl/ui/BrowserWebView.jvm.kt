package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserRuntimeUi
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserSurface
import io.aequicor.heartbeat.feature.browser.impl.presentation.createDesktopPanel

@Composable
internal actual fun BrowserWebView(surface: BrowserSurface, modifier: Modifier) {
    key(surface) {
        val panel = remember(surface) { surface.createDesktopPanel() }
        val preparation by panel.preparation.collectAsState()
        SideEffect { panel.prepare() }
        if (preparation == BrowserRuntimeUi.Ready) {
            // Page loading is shown by the tab's activity indicator, as in Chromium, so the viewport keeps its size.
            BrowserNativeView(
                factory = remember(panel) { panel::createComponent },
                modifier = modifier.fillMaxSize(),
            )
        } else {
            BrowserRuntimeStatus(
                preparation,
                panel::retry,
                modifier,
            )
        }
    }
}
