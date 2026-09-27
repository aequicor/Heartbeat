package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.layouts.hbHorizontalScroll
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbMotion
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbScrollbarUiTest {
    @Test
    fun `edge hover reveals the light scrollbar and leaving fades it away`() {
        verifyHoverVisibility(isDark = false)
    }

    @Test
    fun `edge hover reveals the dark scrollbar and leaving fades it away`() {
        verifyHoverVisibility(isDark = true)
    }

    @Test
    fun `wheel movement reveals the scrollbar without hovering its track`() = runSkikoComposeUiTest(
        size = SCROLLBAR_VIEWPORT,
    ) {
        val state = ScrollState(0)
        setContent { ScrollbarFixture(state = state) }
        val idle = captureToImage().toAwtImage()
        mainClock.autoAdvance = false
        onNodeWithTag(SCROLLBAR_TAG).performMouseInput {
            moveTo(center)
            scroll(3f)
        }
        mainClock.advanceTimeBy(300)
        runOnIdle { assertTrue(state.value > 0, "Wheel input must move the content") }
        assertFalse(equalScrollbarPixels(idle, captureToImage().toAwtImage()), "Scrolling must reveal the thumb")
        // Desktop wheel completion also uses platform scheduling, so yield while advancing frames.
        mainClock.autoAdvance = true
        waitUntil(timeoutMillis = 5000) { !state.isScrollInProgress }
        waitForIdle()
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(2000)
        assertTrue(equalScrollbarPixels(idle, captureToImage().toAwtImage()), "An idle scrollbar must disappear")
    }

    @Test
    fun `content that fits has no scrollbar and its edge remains clickable`() = runSkikoComposeUiTest(
        size = SCROLLBAR_VIEWPORT,
    ) {
        val state = ScrollState(0)
        var clicks = 0
        setContent { ScrollbarFixture(state = state, contentLength = 200, onContentClick = { clicks++ }) }
        val idle = captureToImage().toAwtImage()
        onNodeWithTag(SCROLLBAR_TAG).performMouseInput {
            moveTo(Offset(width - 6f, 60f))
            press()
            release()
            scroll(3f)
        }
        runOnIdle {
            assertEquals(0, state.maxValue)
            assertEquals(0, state.value)
            assertEquals(1, clicks, "A nonexistent thumb must not intercept content input")
        }
        assertTrue(equalScrollbarPixels(idle, captureToImage().toAwtImage()), "Fitting content must not draw a bar")
    }

    @Test
    fun `vertical track and thumb reach the endpoints in both scrolling directions`() {
        listOf(false, true).forEach { verifyTrackAndDrag(orientation = Orientation.Vertical, isReversed = it) }
    }

    @Test
    fun `horizontal track and thumb reach the endpoints in both scrolling directions`() {
        listOf(false, true).forEach { verifyTrackAndDrag(orientation = Orientation.Horizontal, isReversed = it) }
    }

    @Test
    fun `right to left horizontal scrolling maps the thumb to the visual endpoints`() {
        listOf(false, true).forEach {
            verifyTrackAndDrag(Orientation.Horizontal, isReversed = it, layoutDirection = LayoutDirection.Rtl)
        }
    }

    @Test
    fun `a touch swipe at the thin scrollbar edge still scrolls the content`() = runSkikoComposeUiTest(
        size = SCROLLBAR_VIEWPORT,
    ) {
        val state = ScrollState(0)
        setContent { ScrollbarFixture(state = state) }
        onNodeWithTag(SCROLLBAR_TAG).performTouchInput {
            swipe(start = Offset(width - 4f, height - 30f), end = Offset(width - 4f, 30f), durationMillis = 300)
        }
        runOnIdle { assertTrue(state.value > 0, "A touch swipe must not become a mouse track seek") }
    }

    @Test
    fun `lazy thumb stays under the released pointer and dragging reaches both ends`() = runSkikoComposeUiTest(
        size = SCROLLBAR_VIEWPORT,
    ) {
        val state = LazyListState()
        setContent { VariableHeightScrollbarFixture(state) }
        onNodeWithTag(SCROLLBAR_TAG).performMouseInput { moveTo(Offset(width - 6f, centerY)) }
        val initial = renderedThumb(captureToImage().toAwtImage(), Orientation.Vertical)
        onNodeWithTag(SCROLLBAR_TAG).performMouseInput {
            moveTo(scrollbarPoint(Orientation.Vertical, initial.center))
            press()
            moveTo(scrollbarPoint(Orientation.Vertical, SCROLLBAR_VIEWPORT.height / 2f), delayMillis = 100)
        }
        val held = renderedThumb(captureToImage().toAwtImage(), Orientation.Vertical)
        assertTrue(abs(held.center - SCROLLBAR_VIEWPORT.height / 2f) <= 3f, "The thumb must follow the held pointer")
        onNodeWithTag(SCROLLBAR_TAG).performMouseInput { release() }
        val released = renderedThumb(captureToImage().toAwtImage(), Orientation.Vertical)
        assertTrue(abs(held.center - released.center) <= 4f, "Release must not snap the thumb: $held -> $released")
        onNodeWithTag(SCROLLBAR_TAG).dragScrollbarThumb(
            Orientation.Vertical,
            start = released.center,
            end = SCROLLBAR_VIEWPORT.height - 1f,
        )
        runOnIdle { assertFalse(state.canScrollForward, "Dragging to the end must expose the final row") }
        val end = renderedThumb(captureToImage().toAwtImage(), Orientation.Vertical)
        onNodeWithTag(SCROLLBAR_TAG).dragScrollbarThumb(Orientation.Vertical, start = end.center, end = 1f)
        runOnIdle {
            assertFalse(state.canScrollBackward, "Dragging back must expose the first row")
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(0, state.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun `lazy thumb keeps its size and crosses a variable height boundary continuously`() = runSkikoComposeUiTest(
        size = SCROLLBAR_VIEWPORT,
    ) {
        val state = LazyListState(firstVisibleItemIndex = 8)
        setContent { VariableHeightScrollbarFixture(state) }
        onNodeWithTag(SCROLLBAR_TAG).performMouseInput { moveTo(Offset(width - 6f, centerY)) }
        val tallRowThumb = renderedThumb(captureToImage().toAwtImage(), Orientation.Vertical)
        runOnIdle { state.requestScrollToItem(8, 959) }
        val boundaryThumb = renderedThumb(captureToImage().toAwtImage(), Orientation.Vertical)
        onNodeWithTag(SCROLLBAR_TAG).performScrollToIndex(9)
        val shortRowsThumb = renderedThumb(captureToImage().toAwtImage(), Orientation.Vertical)
        assertTrue(
            abs(tallRowThumb.length - shortRowsThumb.length) <= 2,
            "Thumb size must not jump as the visible height sample changes: $tallRowThumb -> $shortRowsThumb",
        )
        assertTrue(
            abs(shortRowsThumb.center - boundaryThumb.center) <= 3f,
            "Crossing the row boundary by one pixel must not jump: $boundaryThumb -> $shortRowsThumb",
        )
        runOnIdle { assertEquals(9, state.firstVisibleItemIndex) }
        saveScrollbarPreview("lazy-variable-height", captureToImage().toAwtImage())
    }

    private fun verifyHoverVisibility(isDark: Boolean) = runSkikoComposeUiTest(size = SCROLLBAR_VIEWPORT) {
        val state = ScrollState(0)
        setContent { ScrollbarFixture(state = state, isDark = isDark) }
        val idle = captureToImage().toAwtImage()
        mainClock.autoAdvance = false
        onNodeWithTag(SCROLLBAR_TAG).performMouseInput { moveTo(Offset(width - 6f, centerY)) }
        mainClock.advanceTimeBy(300)
        val hovered = captureToImage().toAwtImage()
        assertFalse(equalScrollbarPixels(idle, hovered), "Hovering the edge must reveal a discoverable thumb")
        saveScrollbarPreview(if (isDark) "dark-hover" else "light-hover", hovered)
        onNodeWithTag(SCROLLBAR_TAG).performMouseInput { exit() }
        mainClock.advanceTimeBy(300)
        assertFalse(equalScrollbarPixels(idle, captureToImage().toAwtImage()), "The thumb must not vanish on exit")
        mainClock.advanceTimeBy(2000)
        assertTrue(equalScrollbarPixels(idle, captureToImage().toAwtImage()), "The thumb must fade after inactivity")
        runOnIdle { assertEquals(0, state.value, "Hovering must not move the viewport") }
    }

    private fun verifyTrackAndDrag(
        orientation: Orientation,
        isReversed: Boolean,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) = runSkikoComposeUiTest(size = SCROLLBAR_VIEWPORT) {
        val state = ScrollState(0)
        val isRtl = layoutDirection == LayoutDirection.Rtl
        val isVisuallyReversed = isReversed xor (orientation == Orientation.Horizontal && isRtl)
        setContent {
            ScrollbarFixture(
                state = state,
                orientation = orientation,
                isReversed = isReversed,
                isReducedMotion = true,
                layoutDirection = layoutDirection,
            )
        }
        val length = if (orientation == Orientation.Vertical) SCROLLBAR_VIEWPORT.height else SCROLLBAR_VIEWPORT.width
        onNodeWithTag(SCROLLBAR_TAG).performMouseInput {
            moveTo(scrollbarPoint(orientation, length / 2f))
        }
        val initialThumb = renderedThumb(captureToImage().toAwtImage(), orientation)
        assertEquals(isVisuallyReversed, initialThumb.center > length / 2f, "Initial thumb must match the visual start")
        val target = length * if (isVisuallyReversed) 0.25f else 0.75f
        onNodeWithTag(SCROLLBAR_TAG).performMouseInput {
            moveTo(scrollbarPoint(orientation, target))
            press()
            release()
        }
        runOnIdle { assertTrue(state.value > state.maxValue / 2, "Track click must seek toward its location") }
        val soughtThumb = renderedThumb(captureToImage().toAwtImage(), orientation)
        onNodeWithTag(SCROLLBAR_TAG).dragScrollbarThumb(
            orientation,
            start = soughtThumb.center,
            end = if (isVisuallyReversed) 1f else length - 1f,
        )
        runOnIdle { assertEquals(state.maxValue, state.value, "Dragging to the far end must reach the final content") }
        val endThumb = renderedThumb(captureToImage().toAwtImage(), orientation)
        onNodeWithTag(SCROLLBAR_TAG).dragScrollbarThumb(
            orientation,
            start = endThumb.center,
            end = if (isVisuallyReversed) length - 1f else 1f,
        )
        runOnIdle { assertEquals(0, state.value, "Dragging back must reach the first content exactly") }
    }
}

@Composable
private fun ScrollbarFixture(
    state: ScrollState,
    modifier: Modifier = Modifier,
    orientation: Orientation = Orientation.Vertical,
    isReversed: Boolean = false,
    isDark: Boolean = false,
    isReducedMotion: Boolean = false,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    contentLength: Int = 1600,
    onContentClick: () -> Unit = {},
) {
    CompositionLocalProvider(LocalDensity provides Density(1f), LocalLayoutDirection provides layoutDirection) {
        HbTheme(darkTheme = isDark, motion = HbMotion(isReducedMotion = isReducedMotion)) {
            Box(modifier = modifier.fillMaxSize().background(HbTheme.colors.background)) {
                val scrollModifier = if (orientation == Orientation.Vertical) {
                    Modifier.hbVerticalScroll(state, reverseScrolling = isReversed)
                } else {
                    Modifier.hbHorizontalScroll(state, reverseScrolling = isReversed)
                }
                val contentModifier = if (orientation == Orientation.Vertical) {
                    Modifier.fillMaxWidth().height(contentLength.dp)
                } else {
                    Modifier.fillMaxHeight().width(contentLength.dp)
                }
                Box(modifier = Modifier.fillMaxSize().testTag(SCROLLBAR_TAG).then(scrollModifier)) {
                    Box(
                        modifier = contentModifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onContentClick,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun VariableHeightScrollbarFixture(state: LazyListState, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalDensity provides Density(1f)) {
        HbTheme(darkTheme = false, motion = HbMotion(isReducedMotion = true)) {
            HbLazyColumn(
                modifier = modifier.fillMaxSize().background(HbTheme.colors.background).testTag(SCROLLBAR_TAG),
                state = state,
                contentPadding = PaddingValues(0.dp),
                gap = 0.dp,
            ) {
                items(count = 20, key = { it }) { index ->
                    Box(modifier = Modifier.fillMaxWidth().height(if (index == 8) 960.dp else 24.dp))
                }
            }
        }
    }
}

private data class RenderedThumb(val start: Int, val end: Int) {
    val length: Int get() = end - start + 1
    val center: Float get() = (start + end) / 2f
}

/** Reads the actual painted thumb, independent of the scrollbar's geometry calculation. */
private fun renderedThumb(image: BufferedImage, orientation: Orientation): RenderedThumb {
    val background = image.getRGB(image.width / 2, image.height / 2)
    val length = if (orientation == Orientation.Vertical) image.height else image.width
    val contrast = (0 until length).map { position ->
        val color = if (orientation == Orientation.Vertical) {
            image.getRGB(image.width - 4, position)
        } else {
            image.getRGB(position, image.height - 4)
        }
        pixelDistance(background, color)
    }
    val strongest = contrast.max()
    assertTrue(strongest > 0, "The scrollbar must have a visible painted thumb")
    val thumbPixels = contrast.indices.filter { contrast[it] >= strongest * 0.8f }
    return RenderedThumb(thumbPixels.first(), thumbPixels.last())
}

private fun scrollbarPoint(orientation: Orientation, position: Float): Offset =
    if (orientation == Orientation.Vertical) {
        Offset(SCROLLBAR_VIEWPORT.width - 4f, position)
    } else {
        Offset(position, SCROLLBAR_VIEWPORT.height - 4f)
    }

private fun SemanticsNodeInteraction.dragScrollbarThumb(orientation: Orientation, start: Float, end: Float) {
    performMouseInput {
        moveTo(scrollbarPoint(orientation, start))
        press()
        moveTo(scrollbarPoint(orientation, end), delayMillis = 100)
        release()
    }
}

private fun equalScrollbarPixels(first: BufferedImage, second: BufferedImage): Boolean =
    (0 until first.height).all { y ->
        (first.width - 12 until first.width).all { x -> first.getRGB(x, y) == second.getRGB(x, y) }
    }

private fun pixelDistance(first: Int, second: Int): Int =
    listOf(0, 8, 16).sumOf { shift -> abs((first shr shift and 255) - (second shr shift and 255)) }

private fun saveScrollbarPreview(name: String, image: BufferedImage) {
    val directory = File("build/previews")
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create preview directory" }
    check(ImageIO.write(image, "png", File(directory, "scrollbar-$name.png"))) { "PNG encoder is unavailable" }
}

private val SCROLLBAR_VIEWPORT = Size(320f, 240f)
private const val SCROLLBAR_TAG = "scrollbar-viewport"
