package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbComposerUsageUiTest {
    @Test
    fun `full context stroke has the same physical width on straight edges and every corner`() {
        for (dark in listOf(false, true)) {
            runSkikoComposeUiTest(size = Size(960f, 480f)) {
                val density = 4f
                var foreground = 0
                var background = 0
                setContent {
                    UsageTestHost(dark, density = density) {
                        foreground = HbTheme.colors.brand.toArgb()
                        background = HbTheme.surfaces.composerPill.toArgb()
                        HbComposerUsageButton(100, "Usage", false, {}) { }
                    }
                }
                val bounds = onNodeWithContentDescription("Usage").fetchSemanticsNode().boundsInRoot
                val image = captureToImage().toAwtImage().getSubimage(
                    bounds.left.toInt(),
                    bounds.top.toInt(),
                    bounds.width.toInt(),
                    bounds.height.toInt(),
                )
                assertUsageStrokeWidth(image, density, foreground, background, dark)
                val directory = File("build/previews").apply { mkdirs() }
                check(ImageIO.write(image, "png", File(directory, "usage-perimeter-stroke-$dark.png")))
            }
        }
    }

    @Test
    fun `context fills the rounded rectangular perimeter clockwise from its top center`() {
        for (dark in listOf(false, true)) {
            runSkikoComposeUiTest(size = Size(240f, 120f)) {
                var percent by mutableIntStateOf(0)
                var foreground = 0
                var track = 0
                var backdrop = 0
                setContent {
                    UsageTestHost(dark) {
                        foreground = HbTheme.colors.brand.toArgb()
                        track = HbTheme.colors.outlineSubtle.toArgb()
                        backdrop = HbTheme.surfaces.backdrop.toArgb()
                        HbComposerUsageButton(percent, "Usage", false, {}) { }
                    }
                }
                val directory = File("build/previews").apply { mkdirs() }
                for (value in listOf(0, 25, 50, 75, 100)) {
                    runOnIdle { percent = value }
                    val bounds = onNodeWithContentDescription("Usage").fetchSemanticsNode().boundsInRoot
                    val image = captureToImage().toAwtImage().getSubimage(
                        bounds.left.toInt(),
                        bounds.top.toInt(),
                        bounds.width.toInt(),
                        bounds.height.toInt(),
                    )
                    assertUsagePerimeter(image, value, foreground, track, backdrop)
                    check(ImageIO.write(image, "png", File(directory, "usage-perimeter-$value-$dark.png")))
                }
            }
        }
    }

    @Test
    fun `meter updates its percentage and progress semantics including a reported zero`() =
        runSkikoComposeUiTest(size = Size(620f, 500f)) {
            var percent by mutableIntStateOf(61)
            setContent { UsageTestHost { HbComposerUsageButton(percent, "Usage", false, {}) { } } }
            for (value in listOf(61, 0, 100)) {
                runOnIdle { percent = value }
                onNodeWithText("$value%").assertIsDisplayed()
                onNodeWithContentDescription("Usage").assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ProgressBarRangeInfo,
                        ProgressBarRangeInfo(value / 100f, 0f..1f),
                    ),
                )
            }
        }

    @Test
    fun `quota-only button opens without a false context percentage and disabled button cannot open`() =
        runSkikoComposeUiTest(size = Size(620f, 500f)) {
            var isOpen by mutableStateOf(false)
            var isEnabled by mutableStateOf(true)
            setContent {
                UsageTestHost {
                    HbComposerUsageButton(
                        null,
                        "Лимиты",
                        isOpen,
                        { isOpen = it },
                        enabled = isEnabled,
                    ) { UsageDetails() }
                }
            }
            onNodeWithText("0%").assertDoesNotExist()
            onNodeWithContentDescription("Лимиты").performClick()
            onNodeWithText("Context window").assertIsDisplayed()
            runOnIdle {
                isOpen = false
                isEnabled = false
            }
            onNodeWithContentDescription("Лимиты").assertIsNotEnabled().performClick()
            runOnIdle { assertFalse(isOpen) }
        }

    @Test
    fun `keyboard opens detail panel and Escape restores trigger focus`() =
        runSkikoComposeUiTest(size = Size(620f, 500f)) {
            var isOpen by mutableStateOf(false)
            setContent {
                UsageTestHost {
                    HbComposerUsageButton(61, "Usage", isOpen, { isOpen = it }) { UsageDetails() }
                }
            }
            for (key in listOf(Key.Enter, Key.Spacebar, Key.DirectionDown)) {
                onNodeWithContentDescription("Usage").performSemanticsAction(SemanticsActions.RequestFocus)
                onNodeWithContentDescription("Usage").performKeyInput { pressKey(key) }
                onNodeWithText("Context window").assertIsDisplayed()
                onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Usage"))
                    .performKeyInput { pressKey(Key.Escape) }
                onNodeWithContentDescription("Usage").assertIsFocused()
                runOnIdle { assertFalse(isOpen) }
            }
        }

    @Test
    fun `outside click dismisses usage without activating the underlying button`() =
        runSkikoComposeUiTest(size = Size(620f, 500f)) {
            var isOpen by mutableStateOf(false)
            var backgroundClicks = 0
            setContent {
                UsageTestHost {
                    HbButton("Outside", { backgroundClicks++ }, Modifier.align(Alignment.TopEnd))
                    HbComposerUsageButton(61, "Usage", isOpen, { isOpen = it }) { UsageDetails() }
                }
            }
            onNodeWithContentDescription("Usage").performClick()
            onNodeWithText("Outside").performMouseInput {
                moveTo(center)
                press()
                release()
            }
            runOnIdle {
                assertFalse(isOpen)
                assertEquals(0, backgroundClicks)
            }
        }

    @Test
    fun `usage panels fit desktop and mobile in light and dark with readable progress and touch targets`() {
        for (width in listOf(1280f, 420f)) {
            for (dark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width, 800f)) {
                    var isOpen by mutableStateOf(false)
                    setContent {
                        UsageTestHost(dark, isMobile = width == 420f) {
                            HbChatComposer(
                                "", {}, {}, {}, "Send", "Stop",
                                modifier = Modifier.fillMaxWidth().testTag("composer"),
                                layout = HbComposerLayout.Panel,
                                placeholder = "Message",
                                trailingContent = {
                                    HbComposerUsageButton(61, "Usage", isOpen, { isOpen = it }) { UsageDetails() }
                                    HbButton("Model", {}, style = HbButtonStyle.Ghost)
                                },
                            )
                        }
                    }
                    val trigger = onNodeWithContentDescription("Usage")
                    if (width == 420f) {
                        val bounds = trigger.fetchSemanticsNode().boundsInRoot
                        assertTrue(bounds.width >= 48f && bounds.height >= 48f)
                    }
                    trigger.performClick()
                    val panel = onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Usage"))
                        .fetchSemanticsNode().boundsInRoot
                    val composer = onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot
                    assertTrue(panel.left >= 0f && panel.right <= width)
                    assertTrue(panel.bottom <= composer.top)
                    onNodeWithText("Weekly · all models").assertIsDisplayed()
                    val directory = File("build/previews").apply { mkdirs() }
                    check(
                        ImageIO.write(
                            captureToImage().toAwtImage(),
                            "png",
                            File(directory, "usage-${width.toInt()}-$dark.png"),
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `progress bar exposes the same clamped fraction that is drawn`() =
        runSkikoComposeUiTest(size = Size(620f, 500f)) {
            var fraction by mutableFloatStateOf(0.61f)
            setContent { UsageTestHost { HbProgressBar(fraction, contentDescription = "Quota") } }
            for (value in listOf(0.61f, 0f, 1.2f, -0.2f)) {
                runOnIdle { fraction = value }
                onNodeWithContentDescription("Quota").assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ProgressBarRangeInfo,
                        ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f),
                    ),
                )
            }
        }
}

private fun assertUsageStrokeWidth(
    image: BufferedImage,
    density: Float,
    foreground: Int,
    background: Int,
    dark: Boolean,
) {
    val sampleCount = (HbDimensions.Desktop.controlCornerRadius.value * density).toInt()
    val straightSamples = (0 until sampleCount).map { image.width / 2 to it }
    val straightWidth = image.usageAccentPixels(straightSamples, foreground, background).toDouble()
    val expectedWidth = (HbDimensions.Desktop.usageRingStrokeWidth.value * density).toDouble()
    assertEquals(expectedWidth, straightWidth, 1.0, "$dark straight stroke width")
    for ((right, bottom) in listOf(false to false, true to false, true to true, false to true)) {
        val diagonalSamples = (0 until sampleCount).map { step ->
            val x = if (right) image.width - 1 - step else step
            val y = if (bottom) image.height - 1 - step else step
            x to y
        }
        // At 45 degrees a pixel step travels sqrt(2) physical pixels along the stroke normal.
        val cornerWidth = image.usageAccentPixels(diagonalSamples, foreground, background) * sqrt(2.0)
        assertEquals(straightWidth, cornerWidth, 1.0, "$dark corner right=$right bottom=$bottom")
    }
}

private fun assertUsagePerimeter(image: BufferedImage, percent: Int, foreground: Int, track: Int, backdrop: Int) {
    val inset = (HbDimensions.Desktop.usageRingStrokeWidth.value / 2).toInt()
    val corner = HbDimensions.Desktop.controlCornerRadius.value.toInt() + 2
    val right = image.width - inset - 1
    val bottom = image.height - inset - 1
    // Sample the straight edges near corners, where the old ellipse has no outline.
    val edges = listOf(
        image.width - corner - 1 to inset,
        right to image.height - corner - 1,
        corner to bottom,
        inset to corner,
    )
    edges.forEachIndexed { index, (x, y) ->
        val expected = if (percent >= (index + 1) * 25) foreground else track
        image.assertUsagePixel(x, y, expected, "$percent% edge $index")
    }
    image.assertUsagePixel(0, 0, backdrop, "$percent% rounded corner")
    image.assertUsagePixel(image.width / 2, inset, if (percent > 0) foreground else track, "$percent% top seam")
    val midpoint = when (percent) {
        25 -> listOf(right to image.height / 2 - 3, right to image.height / 2 + 3)
        50 -> listOf(image.width / 2 + 3 to bottom, image.width / 2 - 3 to bottom)
        75 -> listOf(inset to image.height / 2 + 3, inset to image.height / 2 - 3)
        else -> emptyList()
    }
    midpoint.forEachIndexed { index, (x, y) ->
        image.assertUsagePixel(x, y, if (index == 0) foreground else track, "$percent% endpoint side $index")
    }
}

private fun BufferedImage.assertUsagePixel(x: Int, y: Int, expected: Int, message: String) {
    val actual = getRGB(x, y)
    val channelsMatch = listOf(16, 8, 0).all { shift ->
        abs((actual shr shift and 255) - (expected shr shift and 255)) <= 4
    }
    assertTrue(channelsMatch, "$message at ($x, $y): expected $expected, actual $actual")
}

private fun BufferedImage.usageAccentPixels(points: List<Pair<Int, Int>>, foreground: Int, background: Int): Int =
    points.count { (x, y) ->
        val pixel = getRGB(x, y)
        usageColorDistance(pixel, foreground) < usageColorDistance(pixel, background)
    }

private fun usageColorDistance(first: Int, second: Int): Int = listOf(16, 8, 0).sumOf { shift ->
    val difference = (first shr shift and 255) - (second shr shift and 255)
    difference * difference
}

@Composable
private fun UsageTestHost(
    dark: Boolean = false,
    isMobile: Boolean = false,
    density: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(LocalDensity provides Density(density)) {
        HbTheme(darkTheme = dark, dimensions = if (isMobile) HbDimensions.Mobile else HbDimensions.Desktop) {
            Box(
                Modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).padding(HbTheme.spacing.xl),
                contentAlignment = Alignment.BottomCenter,
                content = content,
            )
        }
    }
}

@Composable
private fun UsageDetails() {
    HbRow {
        HbText("Context window", Modifier.weight(1f))
        HbText("61%")
    }
    HbProgressBar(0.61f, contentDescription = "Context usage")
    HbDivider()
    HbText("Weekly · all models")
    HbText("Resets Thursday 4:00 PM · 99%", style = HbTheme.typography.caption)
    HbProgressBar(0.99f, color = HbTheme.colors.warning)
}
