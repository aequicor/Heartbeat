package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Native caption geometry in logical units, independent of AWT and the selected UI kit.
 * Insets are physical left/right edges, including in RTL. Fullscreen does not reserve caption space.
 * A host without extended content supplies the default, empty geometry.
 */
@Immutable
data class HbWindowChrome(
    val height: Dp = 0.dp,
    val leftInset: Dp = 0.dp,
    val rightInset: Dp = 0.dp,
    val isFullscreen: Boolean = false,
    val isActive: Boolean = true,
)

private val LocalHbWindowChrome = staticCompositionLocalOf { HbWindowChrome() }
private val LocalHbWindowWidth = staticCompositionLocalOf { 0f }
private val LocalHbCaptionControls = staticCompositionLocalOf<CaptionControls?> { null }

private class CaptionControls {
    var isClient = false
}

/** Marks a control before the root's final hit test, including hover before the first native press. */
@Composable
internal fun Modifier.captionClientArea(): Modifier {
    val controls = LocalHbCaptionControls.current ?: return this
    return pointerInput(controls) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Exit) controls.isClient = true
            }
        }
    }
}

/**
 * Connects window geometry and native hit testing to all pane headers in one Compose root.
 * [onNativeHitTest] receives true for marked client controls (including hover) and consumed gestures,
 * and false for unconsumed events in drag regions.
 * It never consumes Compose input. Native caption actions therefore coexist with editors, menus and buttons.
 */
@Composable
fun HbWindowChromeProvider(
    chrome: HbWindowChrome,
    modifier: Modifier = Modifier,
    onNativeHitTest: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var width by remember { mutableFloatStateOf(0f) }
    val controls = remember { CaptionControls() }
    val captionHeight = with(LocalDensity.current) { chrome.height.toPx() }
    CompositionLocalProvider(
        LocalHbWindowChrome provides chrome,
        LocalHbWindowWidth provides width,
        LocalHbCaptionControls provides controls.takeIf { onNativeHitTest != null && !chrome.isFullscreen },
    ) {
        Box(
            modifier.onSizeChanged { width = it.width.toFloat() }
                .nativeCaptionHitTest(captionHeight, !chrome.isFullscreen, controls, onNativeHitTest),
            propagateMinConstraints = true,
        ) { content() }
    }
}

private fun Modifier.nativeCaptionHitTest(
    height: Float,
    enabled: Boolean,
    controls: CaptionControls,
    onHitTest: ((Boolean) -> Unit)?,
): Modifier {
    if (!enabled || onHitTest == null) return this
    return pointerInput(height, onHitTest) {
        awaitPointerEventScope {
            val gesture = CaptionGesture()
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial)
                controls.isClient = false
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.type == PointerEventType.Exit || event.type == PointerEventType.Scroll) continue
                val isInCaption = event.changes.any { it.position.y in 0f..<height }
                val isClient = !isInCaption || controls.isClient || event.changes.any { it.isConsumed }
                onHitTest(gesture.hitTest(event, isClient))
            }
        }
    }
}

/** Keep a client press in Compose until release, even when a clickable leaves movement unconsumed. */
private class CaptionGesture {
    private var isClient = false

    fun hitTest(event: PointerEvent, client: Boolean): Boolean {
        if (event.type == PointerEventType.Press) isClient = isClient || client
        val isClientEvent = isClient || client
        if (event.type == PointerEventType.Release && event.changes.none { it.pressed }) isClient = false
        return isClientEvent
    }
}

/** Keeps only the portion of native caption exclusions that intersects this header's own bounds. */
@Composable
internal fun WindowCaptionSafeArea(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val chrome = LocalHbWindowChrome.current
    val windowWidth = LocalHbWindowWidth.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val insets = with(density) {
        captionInsets(
            bounds,
            windowWidth,
            chrome.height.toPx(),
            chrome.leftInset.toPx(),
            chrome.rightInset.toPx(),
            chrome.isFullscreen,
        )
    }
    Box(
        modifier.onGloballyPositioned {
            bounds = it.boundsInRoot()
        }.pointerInput(Unit) {
            // Being a pointer target excludes siblings beneath this header from hit testing. Observe without
            // consuming: consuming movement in Final would cancel a child button's press gesture.
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Final)
            }
        },
        propagateMinConstraints = true,
    ) {
        Box(
            Modifier.absolutePadding(
                left = with(density) { insets.first.toDp() },
                right = with(density) { insets.second.toDp() },
            ),
            propagateMinConstraints = true,
        ) { content() }
    }
}

/** Pixel-space intersection; headers below the caption and hidden/fullscreen controls reserve nothing. */
internal fun captionInsets(
    bounds: Rect,
    windowWidth: Float,
    height: Float,
    leftInset: Float,
    rightInset: Float,
    isFullscreen: Boolean = false,
): Pair<Float, Float> {
    if (isFullscreen || bounds.width <= 0f) return 0f to 0f
    if (bounds.top >= height || bounds.bottom <= 0f) return 0f to 0f
    val left = (leftInset - bounds.left).coerceIn(0f, bounds.width)
    val right = (bounds.right - (windowWidth - rightInset)).coerceIn(0f, bounds.width - left)
    return left to right
}
