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
import androidx.compose.ui.test.SkikoComposeUiTest
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
import io.aequicor.heartbeat.ds.tokens.HbMotion
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbComposerUsageUiTest {
    @Test
    fun `context ring takes the brand amber and red accents by occupancy`() {
        for (dark in listOf(false, true)) {
            runSkikoComposeUiTest(size = Size(480f, 240f)) {
                var percent by mutableIntStateOf(40)
                val accents = mutableMapOf<Int, Int>()
                setContent {
                    UsageTestHost(dark, density = 2f) {
                        accents[40] = HbTheme.colors.brand.toArgb()
                        accents[85] = HbTheme.colors.warning.toArgb()
                        accents[100] = HbTheme.colors.error.toArgb()
                        HbComposerUsageButton(percent, "Usage", false, {}) { }
                    }
                }
                val directory = File("build/previews").apply { mkdirs() }
                for (value in listOf(40, 85, 100)) {
                    runOnIdle { percent = value }
                    val image = usageImage()
                    for ((level, accent) in accents) {
                        val message = "$dark $value% shows the $level% accent"
                        assertEquals(level == value, image.hasUsageColor(accent), message)
                    }
                    check(ImageIO.write(image, "png", File(directory, "usage-ring-$value-$dark.png")))
                }
            }
        }
    }

    @Test
    fun `compaction sweeps the ring until it ends and reduced motion keeps the sweep still`() {
        for (isReduced in listOf(false, true)) {
            runSkikoComposeUiTest(size = Size(480f, 240f)) {
                mainClock.autoAdvance = false
                var isCompacting by mutableStateOf(true)
                setContent {
                    UsageTestHost(density = 2f, motion = HbMotion(isReducedMotion = isReduced)) {
                        HbComposerUsageButton(64, "Usage", false, {}, isCompacting = isCompacting) { }
                    }
                }
                mainClock.advanceTimeBy(HbMotion().slowMillis.toLong())
                val first = usageImage()
                mainClock.advanceTimeBy(HbMotion().usageCompactionMillis / 4L)
                assertEquals(isReduced, first.isSameUsage(usageImage()), "reduced=$isReduced sweep moves")
                val directory = File("build/previews").apply { mkdirs() }
                check(ImageIO.write(first, "png", File(directory, "usage-compacting-$isReduced.png")))
                isCompacting = false
                mainClock.advanceTimeBy(HbMotion().slowMillis.toLong())
                val settled = usageImage()
                mainClock.advanceTimeBy(HbMotion().usageCompactionMillis / 4L)
                assertTrue(settled.isSameUsage(usageImage()), "reduced=$isReduced ring rests after compaction")
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

@OptIn(ExperimentalTestApi::class)
private fun SkikoComposeUiTest.usageImage(): BufferedImage {
    val bounds = onNodeWithContentDescription("Usage").fetchSemanticsNode().boundsInRoot
    return captureToImage().toAwtImage().getSubimage(
        bounds.left.toInt(),
        bounds.top.toInt(),
        bounds.width.toInt(),
        bounds.height.toInt(),
    )
}

private fun BufferedImage.hasUsageColor(color: Int): Boolean = (0 until height).any { y ->
    (0 until width).any { x -> usageColorDistance(getRGB(x, y), color) <= USAGE_TOLERANCE }
}

private fun BufferedImage.isSameUsage(other: BufferedImage): Boolean {
    if (width != other.width || height != other.height) return false
    return (0 until height).all { y -> (0 until width).all { x -> getRGB(x, y) == other.getRGB(x, y) } }
}

private const val USAGE_TOLERANCE = 48

private fun usageColorDistance(first: Int, second: Int): Int = listOf(16, 8, 0).sumOf { shift ->
    val difference = (first shr shift and 255) - (second shr shift and 255)
    difference * difference
}

@Composable
private fun UsageTestHost(
    dark: Boolean = false,
    isMobile: Boolean = false,
    density: Float = 1f,
    motion: HbMotion = HbMotion(),
    content: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(LocalDensity provides Density(density)) {
        HbTheme(
            darkTheme = dark,
            dimensions = if (isMobile) HbDimensions.Mobile else HbDimensions.Desktop,
            motion = motion,
        ) {
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
