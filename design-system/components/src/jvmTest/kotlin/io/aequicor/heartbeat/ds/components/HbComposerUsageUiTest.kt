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
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbComposerUsageUiTest {
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
            var open by mutableStateOf(false)
            var enabled by mutableStateOf(true)
            setContent {
                UsageTestHost {
                    HbComposerUsageButton(null, "Лимиты", open, { open = it }, enabled = enabled) { UsageDetails() }
                }
            }
            onNodeWithText("0%").assertDoesNotExist()
            onNodeWithContentDescription("Лимиты").performClick()
            onNodeWithText("Context window").assertIsDisplayed()
            runOnIdle {
                open = false
                enabled = false
            }
            onNodeWithContentDescription("Лимиты").assertIsNotEnabled().performClick()
            runOnIdle { assertFalse(open) }
        }

    @Test
    fun `keyboard opens detail panel and Escape restores trigger focus`() =
        runSkikoComposeUiTest(size = Size(620f, 500f)) {
            var open by mutableStateOf(false)
            setContent {
                UsageTestHost {
                    HbComposerUsageButton(61, "Usage", open, { open = it }) { UsageDetails() }
                }
            }
            for (key in listOf(Key.Enter, Key.Spacebar, Key.DirectionDown)) {
                onNodeWithContentDescription("Usage").performSemanticsAction(SemanticsActions.RequestFocus)
                onNodeWithContentDescription("Usage").performKeyInput { pressKey(key) }
                onNodeWithText("Context window").assertIsDisplayed()
                onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Usage"))
                    .performKeyInput { pressKey(Key.Escape) }
                onNodeWithContentDescription("Usage").assertIsFocused()
                runOnIdle { assertFalse(open) }
            }
        }

    @Test
    fun `outside click dismisses usage without activating the underlying button`() =
        runSkikoComposeUiTest(size = Size(620f, 500f)) {
            var open by mutableStateOf(false)
            var backgroundClicks = 0
            setContent {
                UsageTestHost {
                    HbButton("Outside", { backgroundClicks++ }, Modifier.align(Alignment.TopEnd))
                    HbComposerUsageButton(61, "Usage", open, { open = it }) { UsageDetails() }
                }
            }
            onNodeWithContentDescription("Usage").performClick()
            onNodeWithText("Outside").performMouseInput {
                moveTo(center)
                press()
                release()
            }
            runOnIdle {
                assertFalse(open)
                assertEquals(0, backgroundClicks)
            }
        }

    @Test
    fun `usage panels fit desktop and mobile in light and dark with readable progress and touch targets`() {
        for (width in listOf(1280f, 420f)) {
            for (dark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width, 800f)) {
                    var open by mutableStateOf(false)
                    setContent {
                        UsageTestHost(dark, isMobile = width == 420f) {
                            HbChatComposer(
                                "", {}, {}, {}, "Send", "Stop",
                                modifier = Modifier.fillMaxWidth().testTag("composer"),
                                layout = HbComposerLayout.Panel,
                                placeholder = "Message",
                                trailingContent = {
                                    HbComposerUsageButton(61, "Usage", open, { open = it }) { UsageDetails() }
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

@Composable
private fun UsageTestHost(dark: Boolean = false, isMobile: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    CompositionLocalProvider(LocalDensity provides Density(1f)) {
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
