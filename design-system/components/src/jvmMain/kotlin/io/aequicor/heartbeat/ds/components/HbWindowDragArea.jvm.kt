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
 * Supplies native caption geometry and hit testing inside Compose's Window content.
 * On a plain macOS JDK, Compose dragging remains the fallback. JBR delegates to the native caption instead,
 * preserving system double-click, snap and drag-from-maximized behavior.
 */
@Composable
fun WindowScope.HbWindowDragProvider(
    chrome: HbWindowChrome = HbWindowChrome(),
    onNativeHitTest: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val isMacOs = remember { detectDesktopPlatformUi() == PlatformUi.MacOs }
    val fallback = if (isMacOs && onNativeHitTest == null && !chrome.isFullscreen) this else null
    CompositionLocalProvider(LocalHbWindowScope provides fallback) {
        HbWindowChromeProvider(chrome, onNativeHitTest = onNativeHitTest, content = content)
    }
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
