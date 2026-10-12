package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
class HbPressableRowUiTest {
    @Test
    fun `a pressable row is one button, inert when disabled and as tall as a plain row`() {
        for (dimensions in listOf(HbDimensions.Desktop, HbDimensions.Mobile)) {
            runSkikoComposeUiTest(size = Size(420f, 320f)) {
                var clicks = 0
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        HbTheme(dimensions = dimensions) {
                            HbColumn {
                                HbPressableRow({ clicks++ }, Modifier.testTag("row")) { HbText("Open") }
                                HbPressableRow({ clicks++ }, Modifier.testTag("off"), enabled = false) { HbText("Off") }
                                HbPressableRow(null, Modifier.testTag("plain")) { HbText("Plain") }
                            }
                        }
                    }
                }
                onNodeWithTag("row").assertHasClickAction().performClick()
                onNodeWithTag("off").assertIsNotEnabled().performClick()
                onNodeWithTag("plain").assertHasNoClickAction()
                assertEquals(1, clicks)
                val target = if (dimensions.isDesktop) dimensions.compactControlHeight else dimensions.touchTarget
                onNodeWithTag("row").assertHeightIsAtLeast(target)
                assertEquals(
                    onNodeWithTag("row").fetchSemanticsNode().size.height,
                    onNodeWithTag("plain").fetchSemanticsNode().size.height,
                )
            }
        }
    }

    @Test
    fun `hover fills the row quietly and the keyboard presses it`() = runSkikoComposeUiTest(size = Size(420f, 160f)) {
        var clicks = 0
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                HbTheme(darkTheme = false, motion = HbMotion(isReducedMotion = true)) {
                    Box(Modifier.fillMaxSize().background(HbTheme.colors.background)) {
                        HbPressableRow({ clicks++ }, Modifier.testTag("row")) {
                            HbText("Длинное название строки, которое не помещается и гаснет к краю", maxLines = 1)
                        }
                    }
                }
            }
        }
        val idle = captureToImage().toAwtImage().getRGB(2, 12)
        onNodeWithTag("row").performMouseInput { moveTo(center) }
        waitForIdle()
        assertNotEquals(idle, captureToImage().toAwtImage().getRGB(2, 12), "Hover must fill the row")
        onNodeWithTag("row").performMouseInput { exit() }
        waitForIdle()
        assertEquals(idle, captureToImage().toAwtImage().getRGB(2, 12), "Leaving restores the idle row")

        onNodeWithTag("row").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag("row").performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(1, clicks) }
    }
}
