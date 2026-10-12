package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.aequicor.heartbeat.feature.browser.impl.presentation.AndroidBrowserView
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserSurface

@Composable
internal actual fun BrowserWebView(surface: BrowserSurface, modifier: Modifier) {
    var generation by remember(surface) { mutableIntStateOf(0) }
    key(surface, generation) {
        AndroidView(
            factory = { context -> AndroidBrowserView(context, surface, onRecreate = { generation++ }) },
            modifier = modifier,
            onRelease = AndroidBrowserView::release,
        )
    }
}
