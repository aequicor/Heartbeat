package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserPhase
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenIntent
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenState
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BrowserUiTest {
    @Test
    fun `disabled browser never creates native content and disabling disposes it`() = runSkikoComposeUiTest {
        var state by mutableStateOf(BrowserScreenState(phase = BrowserPhase.Disabled))
        var attached = 0
        var disposed = 0
        setContent {
            HbTheme {
                BrowserScreenContent(state, {}) {
                    DisposableEffect(Unit) {
                        attached++
                        onDispose { disposed++ }
                    }
                }
            }
        }
        onNodeWithTag("browser-open").assertIsNotEnabled()
        runOnIdle {
            assertEquals(0, attached)
            state = state.copy(phase = BrowserPhase.Ready)
        }
        waitForIdle()
        runOnIdle {
            assertEquals(0, attached)
            state = state.copy(url = "https://example.com")
        }
        waitForIdle()
        runOnIdle {
            assertEquals(1, attached)
            state = state.copy(phase = BrowserPhase.Disabled)
        }
        waitForIdle()
        runOnIdle { assertEquals(1, disposed) }
    }

    @Test
    fun `address supports Enter and loading action sends stop`() = runSkikoComposeUiTest {
        var state by mutableStateOf(BrowserScreenState(phase = BrowserPhase.Ready))
        val events = mutableListOf<BrowserScreenIntent>()
        setContent {
            HbTheme {
                BrowserScreenContent(state, {
                    events += it
                    if (it is BrowserScreenIntent.AddressChanged) state = state.copy(address = it.value)
                })
            }
        }
        onNodeWithTag("browser-address").performClick().performTextInput("example.com")
        onNodeWithTag("browser-address").performKeyInput { pressKey(Key.Enter) }
        runOnIdle {
            assertTrue(BrowserScreenIntent.Open in events)
            state = state.copy(url = "https://example.com", isLoading = true)
        }
        onNodeWithTag("browser-reload-stop").performClick()
        runOnIdle { assertEquals(BrowserScreenIntent.Stop, events.last()) }
    }

    @Test
    fun `browser controls fit compact and desktop widths in both themes`() {
        for (width in listOf(320, 420, 1280)) {
            for (dark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width.toFloat(), 800f)) {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f)) {
                            HbTheme(
                                darkTheme = dark,
                                dimensions = if (width == 320) HbDimensions.Mobile else HbDimensions.Desktop,
                            ) {
                                BrowserScreenContent(BrowserScreenState(phase = BrowserPhase.Ready), {})
                            }
                        }
                    }
                    onNodeWithTag("browser-address").assertIsDisplayed()
                    onNodeWithTag("browser-open").assertIsDisplayed()
                    onNodeWithTag("browser-back").assertIsNotEnabled()
                    val directory = File("build/reports/browser").apply { mkdirs() }
                    ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "browser-$width-$dark.png"))
                }
            }
        }
    }
}
