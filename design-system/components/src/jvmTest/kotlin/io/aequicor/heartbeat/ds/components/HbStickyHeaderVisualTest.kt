package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.theme.HbVisualStyle
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbMotion
import kotlinx.collections.immutable.toImmutableList
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbStickyHeaderVisualTest {
    @Test
    fun `light pinned header retains its rounded edge and full shadow over a fading transcript`() {
        verifyPinnedHeader(isDark = false)
    }

    @Test
    fun `dark pinned header retains its rounded edge and full shadow over a fading transcript`() {
        verifyPinnedHeader(isDark = true)
    }

    @Test
    fun `mouse wheel over the pinned header scrolls at the same speed as the transcript`() = runSkikoComposeUiTest(
        size = Size(480f, 420f),
    ) {
        val section = HbChatSection("wheel", "Pinned session")
        val timeline = HbChatTimeline.from(
            section,
            (0 until 40).map { index ->
                HbChatMessage("message-$index", "Agent", "Message $index\nA second line\nA third line")
            }.toImmutableList(),
        )
        val state = LazyListState(firstVisibleItemIndex = 10)
        setContent { StickyHeaderComparison(false, timeline, section, state, referenceBounds = null) }
        val rowSize = runOnIdle { state.layoutInfo.visibleItemsInfo.first { it.index == 10 }.size }
        val before = runOnIdle { state.firstVisibleItemIndex * rowSize + state.firstVisibleItemScrollOffset }
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and
            hasAnyAncestor(hasTestTag("transcript"))
        onNode(heading).performMouseInput {
            moveTo(center)
            scroll(3f)
        }
        waitForIdle()
        val headerDistance = runOnIdle {
            state.firstVisibleItemIndex * rowSize + state.firstVisibleItemScrollOffset - before
        }
        runOnIdle { state.requestScrollToItem(10) }
        waitForIdle()
        onNode(hasTestTag("transcript")).performMouseInput {
            moveTo(center)
            scroll(3f)
        }
        waitForIdle()
        val contentDistance = runOnIdle {
            state.firstVisibleItemIndex * rowSize + state.firstVisibleItemScrollOffset - before
        }
        assertTrue(headerDistance > 0, "A wheel gesture over the pinned header must scroll the history")
        assertTrue(
            abs(headerDistance - contentDistance) <= 2,
            "Equal wheel input must travel equally over header ($headerDistance) and rows ($contentDistance)",
        )
    }

    private fun verifyPinnedHeader(isDark: Boolean) = runSkikoComposeUiTest(size = Size(960f, 420f)) {
        val section = HbChatSection("visual", "Pinned session")
        val timeline = HbChatTimeline.from(
            section,
            (0 until 40).map { index ->
                HbChatMessage(
                    id = "message-$index",
                    author = "Agent",
                    text = "Message $index\nA second line\nA third line",
                    appearance = HbMessageAppearance(widthFraction = 0.45f),
                )
            }.toImmutableList(),
        )
        val state = LazyListState(firstVisibleItemIndex = 10)
        var referenceBounds by mutableStateOf<Rect?>(null)
        setContent {
            StickyHeaderComparison(isDark, timeline, section, state, referenceBounds)
        }
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and
            hasAnyAncestor(hasTestTag("transcript"))
        val bounds = onNode(heading).fetchSemanticsNode().boundsInRoot
        runOnIdle {
            assertTrue(state.canScrollBackward && state.canScrollForward, "Both transcript fade edges must be active")
            referenceBounds = bounds
        }
        val image = captureToImage().toAwtImage()
        saveStickyHeaderPreview(if (isDark) "dark" else "light", image)
        val background = (if (isDark) HbColors.DesktopDark else HbColors.DesktopLight).background.toArgb()
        assertShadowMatchesReference(image, bounds, background)
        assertRoundedEdgeMatchesReference(image, bounds)
    }
}

@Composable
private fun StickyHeaderComparison(
    isDark: Boolean,
    timeline: HbChatTimeline,
    section: HbChatSection,
    state: LazyListState,
    referenceBounds: Rect?,
) {
    CompositionLocalProvider(LocalDensity provides Density(1f)) {
        HbTheme(darkTheme = isDark, visualStyle = HbVisualStyle.Glass, motion = HbMotion(isReducedMotion = true)) {
            // No Haze scene: only alpha compositing and the actual header's shadow are under test.
            Box(modifier = Modifier.fillMaxSize().background(HbTheme.colors.background)) {
                HbChatTranscript(
                    timeline = timeline,
                    modifier = Modifier.width(480.dp).fillMaxHeight().testTag("transcript"),
                    state = state,
                )
                referenceBounds?.let { bounds ->
                    HbTranscriptSectionHeader(
                        section = section,
                        modifier = Modifier.offset(x = (480f + bounds.left).dp, y = bounds.top.dp)
                            .width(bounds.width.dp),
                    )
                }
            }
        }
    }
}

private fun assertShadowMatchesReference(image: BufferedImage, bounds: Rect, background: Int) {
    val left = bounds.right.roundToInt() - 48
    val right = bounds.right.roundToInt() - 12
    val top = bounds.bottom.roundToInt()
    val bottom = minOf(top + 16, image.height)
    var actualEnergy = 0L
    var referenceEnergy = 0L
    for (y in top until bottom) {
        for (x in left until right) {
            actualEnergy += colorDistance(image.getRGB(x, y), background)
            referenceEnergy += colorDistance(image.getRGB(x + 480, y), background)
        }
    }
    assertTrue(referenceEnergy > 30, "The reference must contain a visible shadow, measured $referenceEnergy")
    assertTrue(
        actualEnergy >= referenceEnergy * 0.9 && actualEnergy <= referenceEnergy * 1.1,
        "Pinned header shadow must survive the fade boundary: actual=$actualEnergy, reference=$referenceEnergy",
    )
}

private fun assertRoundedEdgeMatchesReference(image: BufferedImage, bounds: Rect) {
    val left = bounds.right.roundToInt() - 16
    val right = minOf(bounds.right.roundToInt() + 10, 480)
    val top = bounds.top.roundToInt().coerceAtLeast(0)
    val bottom = minOf(bounds.bottom.roundToInt() + 16, image.height)
    var difference = 0L
    for (y in top until bottom) {
        for (x in left until right) {
            difference += colorDistance(image.getRGB(x, y), image.getRGB(x + 480, y))
        }
    }
    val meanChannelDifference = difference.toDouble() / ((right - left) * (bottom - top) * 3)
    assertTrue(
        meanChannelDifference <= 0.5,
        "Pinned rounded corner and shadow must match an unfaded header, mean channel error=$meanChannelDifference",
    )
}

private fun colorDistance(first: Int, second: Int): Int = abs((first shr 16 and 255) - (second shr 16 and 255)) +
    abs((first shr 8 and 255) - (second shr 8 and 255)) +
    abs((first and 255) - (second and 255))

private fun saveStickyHeaderPreview(theme: String, image: BufferedImage) {
    val directory = File("build/previews")
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create preview directory" }
    check(ImageIO.write(image, "png", File(directory, "sticky-header-$theme.png"))) { "PNG encoder is unavailable" }
}
