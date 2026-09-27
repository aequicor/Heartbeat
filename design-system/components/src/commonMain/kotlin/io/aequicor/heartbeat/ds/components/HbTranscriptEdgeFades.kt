package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Alpha masks soften lazy viewport overlap without painting over the scene or intercepting input.
 * Supply [stickyHeaderKeyPrefix] only with [HbStickyHeaderHost], which paints its pinned header
 * outside this layer. Its body is cleared here so raw content cannot leak through rounded corners.
 */
@Composable
public fun Modifier.hbLazyEdgeFades(state: LazyListState, stickyHeaderKeyPrefix: String? = null): Modifier {
    val opaqueMask = HbTheme.colors.background.copy(alpha = 1f)
    val fade = HbTheme.dimensions.transcriptEdgeFade
    return graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
    }.drawWithCache {
        val height = fade.toPx().coerceAtMost(size.height / 2f)
        val transparentMask = opaqueMask.copy(alpha = 0f)
        val bottom = Brush.verticalGradient(listOf(opaqueMask, transparentMask), size.height - height, size.height)
        onDrawWithContent {
            drawContent()
            if (state.canScrollBackward) {
                drawTopContentFade(state, stickyHeaderKeyPrefix, height, opaqueMask)
            }
            if (state.canScrollForward) {
                applyAlphaBand(bottom, size.height - height, height)
            }
        }
    }
}

private fun DrawScope.drawTopContentFade(state: LazyListState, keyPrefix: String?, height: Float, opaqueMask: Color) {
    val pinned = keyPrefix?.let { state.pinnedHeader(it) }
    val top = ((pinned?.top ?: 0) + (pinned?.size ?: 0)).toFloat().coerceIn(0f, size.height)
    val transparentMask = opaqueMask.copy(alpha = 0f)
    if (top > 0f) applyAlphaBand(SolidColor(transparentMask), 0f, top)
    // The next native header must keep its own surface while it pushes the old overlay away.
    val nextHeaderTop = state.nextHeaderTop(keyPrefix, pinned?.key) ?: size.height
    val fadeHeight = height.coerceAtMost((nextHeaderTop - top).coerceAtLeast(0f))
    if (fadeHeight > 0f) {
        applyAlphaBand(
            Brush.verticalGradient(listOf(transparentMask, opaqueMask), top, top + fadeHeight),
            top,
            fadeHeight,
        )
    }
}

private fun LazyListState.nextHeaderTop(keyPrefix: String?, pinnedKey: String?): Float? {
    if (keyPrefix == null) return null
    val info = layoutInfo
    return info.visibleItemsInfo.firstOrNull {
        it.key != pinnedKey && it.key.toString().startsWith(keyPrefix) && it.offset > 0
    }?.let { (it.offset - info.viewportStartOffset).toFloat() }
}

private fun DrawScope.applyAlphaBand(brush: Brush, top: Float, height: Float) {
    // DstIn only touches its band; the rest of the scrolling layer remains intact.
    clipRect(top = top, bottom = (top + height).coerceAtMost(size.height)) {
        drawRect(
            brush,
            topLeft = Offset(0f, top),
            size = Size(size.width, height),
            blendMode = BlendMode.DstIn,
        )
    }
}
