package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserRuntimeUi
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserSurface
import io.aequicor.heartbeat.feature.browser.impl.presentation.createDesktopPanel
import io.aequicor.heartbeat.feature.browser.impl.resources.Res
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_loading
import org.jetbrains.compose.resources.stringResource

@Composable
internal actual fun BrowserWebView(surface: BrowserSurface, modifier: Modifier) {
    key(surface) {
        val panel = remember(surface) { surface.createDesktopPanel() }
        val preparation by panel.preparation.collectAsState()
        SideEffect { panel.prepare() }
        if (preparation == BrowserRuntimeUi.Ready) {
            val isLoading by panel.loading.collectAsState()
            HbColumn(modifier, gap = HbTheme.spacing.none) {
                if (isLoading) HbLoadingState(stringResource(Res.string.browser_loading))
                BrowserNativeView(
                    factory = remember(panel) { panel::createComponent },
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
            }
        } else {
            BrowserRuntimeStatus(
                preparation,
                panel::retry,
                modifier,
            )
        }
    }
}
