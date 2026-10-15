package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserRuntimeProblem
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserRuntimeUi
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BrowserRuntimeStatusTest {
    @Test
    fun `download progress and keyboard retry fit compact and desktop viewports in both themes`() {
        for (width in listOf(420, 1280)) {
            for (dark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width.toFloat(), 600f)) {
                    var state: BrowserRuntimeUi by mutableStateOf(BrowserRuntimeUi.Downloading(64, 37))
                    var retries = 0
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f)) {
                            HbTheme(darkTheme = dark, dimensions = HbDimensions.Desktop) {
                                BrowserRuntimeStatus(state, { retries++ }, Modifier.fillMaxSize())
                            }
                        }
                    }
                    onNodeWithTag("browser-runtime-progress").assertIsDisplayed().assert(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.ProgressBarRangeInfo,
                            ProgressBarRangeInfo(0.37f, 0f..1f),
                        ),
                    )
                    onAllNodesWithText("%%", substring = true).assertCountEquals(0)
                    val directory = File("build/reports/browser").apply { mkdirs() }
                    ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "download-$width-$dark.png"))
                    runOnIdle { state = BrowserRuntimeUi.Failed(BrowserRuntimeProblem.Network) }
                    onNodeWithTag("browser-runtime-retry").assertIsDisplayed()
                    onNodeWithTag("browser-runtime-status").performKeyInput { pressKey(Key.Tab) }
                    onNodeWithTag("browser-runtime-retry").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
                    runOnIdle { assertEquals(1, retries) }
                    ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "retry-$width-$dark.png"))
                }
            }
        }
    }
}
