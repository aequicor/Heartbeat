package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbMotion
import kotlinx.collections.immutable.persistentListOf
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbTranscriptSpacingUiTest {
    @Test
    fun `compact transcript separates its section header and keeps shared canvas gutters`() {
        verifySpacing(width = 390f)
    }

    @Test
    fun `wide transcript separates its section header and keeps shared canvas gutters`() {
        verifySpacing(width = 960f)
    }

    private fun verifySpacing(width: Float) = runSkikoComposeUiTest(size = Size(width, 480f)) {
        val state = LazyListState()
        val timeline = spacingTimeline()
        setContent { TranscriptSpacingHost(timeline, state) }
        val heading = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .fetchSemanticsNode().boundsInRoot
        val author = onNodeWithText("Reader").fetchSemanticsNode().boundsInRoot
        val first = onNodeWithText("First paragraph").fetchSemanticsNode().boundsInRoot
        val last = onNodeWithText("Second paragraph").fetchSemanticsNode().boundsInRoot
        runOnIdle {
            assertFalse(state.canScrollBackward || state.canScrollForward, "The fixture must fit without scrolling")
            val info = state.layoutInfo
            val firstMessage = info.visibleItemsInfo.first { it.index == 1 }
            val firstMessageTop = (firstMessage.offset - info.viewportStartOffset).toFloat()
            assertClose(8f, firstMessageTop - heading.bottom, "Header to first bubble separation")
            assertClose(12f, author.top - firstMessageTop, "First message retains its interior padding")
            assertClose(12f, heading.left, "Header left gutter")
            assertClose(12f, width - heading.right, "Header right gutter")
            assertClose(12f, heading.top, "Header top gutter")
            assertClose(24f, first.left, "Assistant text uses the shared gutter plus bubble padding")
            assertEquals(12, info.beforeContentPadding)
            assertEquals(12, info.afterContentPadding)
            assertClose(6f, last.top - first.bottom, "Markdown block separation is applied once")
            val finalItem = info.visibleItemsInfo.last()
            val finalItemBottom = (finalItem.offset - info.viewportStartOffset + finalItem.size).toFloat()
            assertClose(12f, finalItemBottom - last.bottom, "Final bubble has no extra inter-message gap")
        }
        val image = captureToImage().toAwtImage()
        val readingSurface = HbColors.DesktopLight.assistantSurface.toArgb()
        // This strip is inside the bubble's left padding and crosses the boundary between its lazy chunks.
        for (y in first.bottom.roundToInt() - 1..last.top.roundToInt() + 1) {
            assertEquals(readingSurface, image.getRGB(18, y), "Assistant surface must remain continuous at y=$y")
        }
        saveSpacingPreview(width.roundToInt(), image)
    }
}

@Composable
private fun TranscriptSpacingHost(timeline: HbChatTimeline, state: LazyListState, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalDensity provides Density(1f)) {
        HbTheme(darkTheme = false, motion = HbMotion(isReducedMotion = true)) {
            Box(modifier = modifier.fillMaxSize().background(HbTheme.colors.background)) {
                HbChatTranscript(timeline = timeline, state = state, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

private fun spacingTimeline(): HbChatTimeline = HbChatTimeline.from(
    HbChatSection("today", "Today · Studio"),
    persistentListOf(
        HbChatMessage(
            id = "question",
            author = "Reader",
            text = "A short question",
            role = HbChatRole.User,
            appearance = HbMessageAppearance(tone = HbTone.Brand, widthFraction = 1f),
        ),
        HbChatMessage(
            id = "answer",
            author = "Agent",
            text = "First paragraph\n\nSecond paragraph",
            kind = HbMessageKind.Markdown,
            appearance = HbMessageAppearance(widthFraction = 1f),
        ),
    ),
)

private fun assertClose(expected: Float, actual: Float, description: String) {
    assertTrue(abs(expected - actual) <= 1f, "$description: expected=$expected, actual=$actual")
}

private fun saveSpacingPreview(width: Int, image: BufferedImage) {
    val directory = File("build/previews")
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create preview directory" }
    check(ImageIO.write(image, "png", File(directory, "transcript-spacing-$width.png"))) {
        "PNG encoder is unavailable"
    }
}
