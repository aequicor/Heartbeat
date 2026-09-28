package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.WindowScope
import io.aequicor.heartbeat.ds.adaptive.PlatformUi
import io.aequicor.heartbeat.ds.adaptive.detectDesktopPlatformUi

private val LocalHbWindowScope = staticCompositionLocalOf<WindowScope?> { null }

/**
 * Supplies the native macOS window to [HbWindowDragArea] descendants.
 * Call inside Compose's Window content. Other operating systems keep their native titlebars
 * and do not install custom drag handling, regardless of the selected visual UI kit.
 */
@Composable
fun WindowScope.HbWindowDragProvider(content: @Composable () -> Unit) {
    val isMacOs = remember { detectDesktopPlatformUi() == PlatformUi.MacOs }
    CompositionLocalProvider(LocalHbWindowScope provides if (isMacOs) this else null, content = content)
}

@Composable
internal actual fun HbPlatformWindowDragArea(modifier: Modifier, content: @Composable () -> Unit) {
    val scope = LocalHbWindowScope.current
    if (scope == null) {
        Box(modifier, propagateMinConstraints = true) { content() }
    } else {
        scope.WindowDraggableArea(modifier, content)
    }
}
