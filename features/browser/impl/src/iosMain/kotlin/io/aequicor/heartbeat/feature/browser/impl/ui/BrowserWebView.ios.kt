package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserSurface
import io.aequicor.heartbeat.feature.browser.impl.presentation.IosBrowserController
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun BrowserWebView(surface: BrowserSurface, modifier: Modifier) {
    key(surface) {
        val controller = remember(surface) { IosBrowserController(surface) }
        UIKitView(
            factory = controller::createView,
            modifier = modifier,
            onRelease = { controller.release() },
        )
    }
}
