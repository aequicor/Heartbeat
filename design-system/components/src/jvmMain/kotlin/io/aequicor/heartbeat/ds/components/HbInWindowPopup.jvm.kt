package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

private val LocalHbInWindowPopups = compositionLocalOf<InWindowPopups?> { null }

/**
 * Non-modal sheets share their owner's canvas: no AWT window, focus transfer or outside-input backdrop.
 * The window portal installs a separate host inside each modal focus scope so suggestions cannot escape it.
 * Focusable menus retain WINDOW layers to cover heavyweight native browser peers.
 */
@Composable
internal fun HbInWindowPopupHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val popups = remember { InWindowPopups() }
    CompositionLocalProvider(LocalHbInWindowPopups provides popups) {
        Box(modifier.fillMaxSize()) {
            content()
            popups.entries.forEach { it() }
        }
    }
}

/** Standalone previews without a window host keep the platform popup fallback. */
@Composable
internal fun HbNonFocusablePopup(
    position: PopupPositionProvider,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val popups = LocalHbInWindowPopups.current
    if (popups == null) {
        Popup(position, onDismissRequest, PopupProperties(focusable = false), content)
        return
    }
    var anchor by remember { mutableStateOf<IntRect?>(null) }
    Box(
        Modifier.onGloballyPositioned {
            // boundsInWindow clips a zero-size marker to Rect.Zero; keep its actual window position.
            anchor = IntRect(it.positionInWindow().round(), it.size)
        },
    )
    val locals by rememberUpdatedState(currentCompositionLocalContext)
    val provider by rememberUpdatedState(position)
    val body by rememberUpdatedState(content)
    val entry: @Composable () -> Unit = remember(popups) {
        {
            CompositionLocalProvider(locals) {
                InWindowPopupSheet({ anchor }, provider, body)
            }
        }
    }
    DisposableEffect(entry) {
        popups.entries.add(entry)
        onDispose { popups.entries.remove(entry) }
    }
}

/** Measures against the window, converting window-relative anchor coordinates to this host's origin. */
@Composable
private fun InWindowPopupSheet(
    anchor: () -> IntRect?,
    position: PopupPositionProvider,
    content: @Composable () -> Unit,
) {
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    val window = LocalWindowInfo.current.containerSize
    Layout(
        content = { Box { content() } },
        modifier = Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow().round() },
    ) { measurables, constraints ->
        val sheet = measurables.single().measure(Constraints(maxWidth = window.width, maxHeight = window.height))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val bounds = anchor() ?: return@layout
            sheet.place(
                position.calculatePosition(
                    bounds,
                    window,
                    layoutDirection,
                    IntSize(sheet.width, sheet.height),
                ) - origin,
            )
        }
    }
}

private class InWindowPopups {
    val entries = mutableStateListOf<@Composable () -> Unit>()
}
