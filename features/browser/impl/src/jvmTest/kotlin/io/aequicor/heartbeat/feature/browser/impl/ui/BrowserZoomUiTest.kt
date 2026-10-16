package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.test.withKeyDown
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.browser.api.browserZoomStep
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserShortcut
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserZoomDirection
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserPhase
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenIntent
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenState
import io.aequicor.heartbeat.feature.browser.impl.presentation.toDomain
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BrowserZoomUiTest {
    @Test
    fun `zoom badge controls respect bounds reset and escape across widths and themes`() {
        for (width in listOf(420, 1280)) {
            for (dark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width.toFloat(), 600f)) {
                    var state by mutableStateOf(
                        BrowserScreenState(
                            phase = BrowserPhase.Ready,
                            hasTabs = true,
                            isZoomEnabled = true,
                            zoomPercent = 125,
                            url = "https://example.com/",
                        ),
                    )
                    val events = mutableListOf<BrowserZoomDirection>()
                    setContent {
                        HbTheme(darkTheme = dark, dimensions = HbDimensions.Desktop) {
                            BrowserScreenContent(state, {
                                val zoom = (it as? BrowserScreenIntent.Shortcut)?.shortcut as? BrowserShortcut.Zoom
                                if (zoom != null) {
                                    events += zoom.direction
                                    state = state.copy(
                                        zoomPercent = browserZoomStep(state.zoomPercent, zoom.direction.toDomain()),
                                    )
                                }
                            })
                        }
                    }
                    onNodeWithTag("browser-zoom").performClick()
                    onNodeWithTag("browser-zoom-in").assertIsDisplayed().performClick()
                    runOnIdle { assertEquals(150, state.zoomPercent) }
                    onNodeWithTag("browser-zoom-out").performClick()
                    runOnIdle { assertEquals(125, state.zoomPercent) }
                    val directory = File("build/reports/browser-zoom").apply { mkdirs() }
                    ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "zoom-$width-$dark.png"))
                    onNodeWithTag("browser-zoom-in").performKeyInput { pressKey(Key.Escape) }
                    onNodeWithTag("browser-zoom-percent").assertDoesNotExist()
                    onNodeWithTag("browser-zoom").assertIsFocused().performClick()
                    runOnIdle { state = state.copy(zoomPercent = 500) }
                    onNodeWithTag("browser-zoom-in").assertIsNotEnabled()
                    runOnIdle { state = state.copy(zoomPercent = 25) }
                    onNodeWithTag("browser-zoom-out").assertIsNotEnabled()
                    onNodeWithTag("browser-zoom-reset").performClick()
                    onNodeWithTag("browser-zoom").assertDoesNotExist()
                    onNodeWithTag("browser-zoom-percent").assertDoesNotExist()
                    runOnIdle { assertEquals(100, state.zoomPercent) }
                    runOnIdle { state = state.copy(zoomPercent = 150, isZoomEnabled = false) }
                    onNodeWithTag("browser-zoom").assertDoesNotExist()
                }
            }
        }
    }

    @Test
    fun `popup handles host zoom shortcuts once while one of its buttons has focus`() = runSkikoComposeUiTest {
        var state by mutableStateOf(zoomState())
        val events = mutableListOf<BrowserZoomDirection>()
        val accelerator = if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft
        setContent {
            HbTheme(dimensions = HbDimensions.Desktop) {
                BrowserScreenContent(state, { intent ->
                    val zoom = (intent as? BrowserScreenIntent.Shortcut)?.shortcut as? BrowserShortcut.Zoom
                    if (zoom != null) {
                        events += zoom.direction
                        state = state.copy(
                            zoomPercent = browserZoomStep(state.zoomPercent, zoom.direction.toDomain()),
                        )
                    }
                })
            }
        }
        onNodeWithTag("browser-zoom").performClick()
        onNodeWithTag("browser-zoom-in").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag("browser-zoom-in").assertIsFocused().performKeyInput {
            withKeyDown(accelerator) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Equals) } }
        }
        runOnIdle { assertEquals(150, state.zoomPercent) }
        onNodeWithTag("browser-zoom-in").performKeyInput {
            withKeyDown(accelerator) { pressKey(Key.Minus) }
        }
        runOnIdle { assertEquals(125, state.zoomPercent) }
        onNodeWithTag("browser-zoom-in").performKeyInput {
            withKeyDown(accelerator) { pressKey(Key.Zero) }
        }
        runOnIdle {
            assertEquals(100, state.zoomPercent)
            assertEquals(listOf(BrowserZoomDirection.In, BrowserZoomDirection.Out, BrowserZoomDirection.Reset), events)
        }
        onNodeWithTag("browser-zoom-percent").assertDoesNotExist()
        onNodeWithTag("browser-zoom").assertDoesNotExist()
    }

    @Test
    fun `open panel stays usable when stepping through default zoom in both directions`() = runSkikoComposeUiTest {
        var state by mutableStateOf(zoomState().copy(zoomPercent = 110))
        setContent {
            HbTheme(dimensions = HbDimensions.Desktop) {
                BrowserScreenContent(state, { intent ->
                    val zoom = (intent as? BrowserScreenIntent.Shortcut)?.shortcut as? BrowserShortcut.Zoom
                    if (zoom != null) {
                        state = state.copy(zoomPercent = browserZoomStep(state.zoomPercent, zoom.direction.toDomain()))
                    }
                })
            }
        }
        onNodeWithTag("browser-zoom").performClick()
        onNodeWithTag("browser-zoom-out").performClick()
        runOnIdle { assertEquals(100, state.zoomPercent) }
        onNodeWithTag("browser-zoom-percent").assertIsDisplayed()
        onNodeWithTag("browser-zoom-out").performClick()
        runOnIdle { assertEquals(90, state.zoomPercent) }
        onNodeWithTag("browser-zoom-in").performClick()
        runOnIdle { assertEquals(100, state.zoomPercent) }
        onNodeWithTag("browser-zoom-percent").assertIsDisplayed()
        onNodeWithTag("browser-zoom-in").performClick()
        runOnIdle { assertEquals(110, state.zoomPercent) }
        onNodeWithTag("browser-zoom-out").performClick()
        onNodeWithTag("browser-zoom-in").performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag("browser-zoom-percent").assertDoesNotExist()
        onNodeWithTag("browser-zoom").assertDoesNotExist()
    }

    @Test
    fun `tab switch navigation and disabling zoom dismiss the old panel`() = runSkikoComposeUiTest {
        var state by mutableStateOf(zoomState())
        setContent {
            HbTheme(dimensions = HbDimensions.Desktop) { BrowserScreenContent(state, {}) }
        }
        onNodeWithTag("browser-zoom").performClick()
        onNodeWithTag("browser-zoom-percent").assertIsDisplayed()
        runOnIdle { state = state.copy(selectedTabId = "second") }
        onNodeWithTag("browser-zoom-percent").assertDoesNotExist()
        onNodeWithTag("browser-zoom").performClick()
        onNodeWithTag("browser-zoom-percent").assertIsDisplayed()
        runOnIdle { state = state.copy(url = "https://example.com/next") }
        onNodeWithTag("browser-zoom-percent").assertDoesNotExist()
        onNodeWithTag("browser-zoom").performClick()
        onNodeWithTag("browser-zoom-percent").assertIsDisplayed()
        runOnIdle { state = state.copy(isZoomEnabled = false) }
        onNodeWithTag("browser-zoom-percent").assertDoesNotExist()
        onNodeWithTag("browser-zoom").assertDoesNotExist()
        runOnIdle { state = state.copy(isZoomEnabled = true) }
        onNodeWithTag("browser-zoom-percent").assertDoesNotExist()
        onNodeWithTag("browser-zoom").assertIsDisplayed()
    }
}

private fun zoomState() = BrowserScreenState(
    phase = BrowserPhase.Ready,
    hasTabs = true,
    isZoomEnabled = true,
    zoomPercent = 125,
    selectedTabId = "first",
    url = "https://example.com/",
)
