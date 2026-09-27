package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider

/** Keeps menus above the entire composer, including when their trigger is in its bottom toolbar. */
internal val LocalComposerAnchor = staticCompositionLocalOf<State<IntRect?>?> { null }

internal class ComposerPopupPosition(
    private val composerBounds: IntRect?,
    private val gutter: Int,
    private val gap: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val desiredX = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.left - gutter
        } else {
            anchorBounds.right - popupContentSize.width + gutter
        }
        val desiredY = (composerBounds ?: anchorBounds).top - gap - popupContentSize.height + gutter
        return IntOffset(
            x = desiredX.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y = desiredY.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}
