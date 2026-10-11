package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.layouts.LocalPaneExpansionAvailable
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.BrowserPaneMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Exercises the production studio host; the browser slot is a module-independent content fixture. */
@OptIn(ExperimentalTestApi::class)
class StudioBrowserLayoutUiTest {
    @Test
    fun `right pane resizes by dragging and retains its content across expansion and return`() =
        runSkikoComposeUiTest(size = Size(1280f, 720f)) {
            var mode by mutableStateOf(BrowserPaneMode.Panel)
            var mounts = 0
            var disposals = 0
            setContent {
                HbTheme(dimensions = HbDimensions.Desktop) {
                    StudioBrowserLayout(
                        mode,
                        Modifier.testTag("workspace"),
                        browser = { BrowserContent({ mounts++ }, { disposals++ }) },
                        chat = { Box(Modifier.fillMaxSize().testTag("conversation")) },
                    )
                }
            }
            fun bounds(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val workspace = bounds("workspace")
            val initial = bounds("browser-panel")
            val initialChat = bounds("conversation")
            assertEquals(workspace.right, initial.right, 1f)
            assertTrue(initial.left > initialChat.right, "The divider separates chat from the right pane")
            onNodeWithTag("browser-resize").performTouchInput {
                swipe(center, center + Offset(-160f, 0f), durationMillis = 300)
            }
            val resized = bounds("browser-panel")
            assertTrue(resized.width > initial.width + 100f, "Dragging left must enlarge the browser")
            assertTrue(bounds("conversation").width < initialChat.width)
            assertEquals(workspace.right, resized.right, 1f)
            runOnIdle { mode = BrowserPaneMode.Expanded }
            onNodeWithTag("conversation").assertDoesNotExist()
            onNodeWithTag("browser-resize").assertDoesNotExist()
            assertBounds(workspace, bounds("browser-content"))
            runOnIdle { mode = BrowserPaneMode.Panel }
            onNodeWithTag("conversation").assertIsDisplayed()
            onNodeWithTag("browser-resize").assertIsDisplayed()
            assertBounds(resized, bounds("browser-panel"))
            runOnIdle {
                assertEquals(1, mounts, "Resizing and changing presentation must preserve the browser slot")
                assertEquals(0, disposals)
            }
        }

    @Test
    fun `narrow workspace expands automatically then restores the panel without remounting`() =
        runSkikoComposeUiTest(size = Size(1280f, 720f)) {
            var width by mutableStateOf(1280.dp)
            var mounts = 0
            var disposals = 0
            var isExpansionAvailable = false
            setContent {
                HbTheme(dimensions = HbDimensions.Desktop) {
                    StudioBrowserLayout(
                        BrowserPaneMode.Panel,
                        Modifier.width(width).testTag("workspace"),
                        browser = {
                            val isAvailable = LocalPaneExpansionAvailable.current
                            SideEffect { isExpansionAvailable = isAvailable }
                            BrowserContent({ mounts++ }, { disposals++ })
                        },
                        chat = { Box(Modifier.fillMaxSize().testTag("conversation")) },
                    )
                }
            }
            fun bounds(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val panelWidth = bounds("browser-panel").width
            runOnIdle { assertTrue(isExpansionAvailable) }
            runOnIdle { width = 420.dp }
            onNodeWithTag("conversation").assertDoesNotExist()
            onNodeWithTag("browser-resize").assertDoesNotExist()
            assertBounds(bounds("workspace"), bounds("browser-content"))
            runOnIdle { assertFalse(isExpansionAvailable, "The browser must use its host width, not the wider window") }
            runOnIdle { width = 1280.dp }
            onNodeWithTag("conversation").assertIsDisplayed()
            onNodeWithTag("browser-resize").assertIsDisplayed()
            assertEquals(panelWidth, bounds("browser-panel").width, 1f)
            runOnIdle {
                assertTrue(isExpansionAvailable)
                assertEquals(1, mounts)
                assertEquals(0, disposals)
            }
        }

    @Test
    fun `divider follows the current theme and hiding returns all space to the conversation`() =
        runSkikoComposeUiTest(size = Size(1280f, 720f)) {
            var mode by mutableStateOf(BrowserPaneMode.Panel)
            var isDark by mutableStateOf(false)
            var expectedDivider = Color.Unspecified
            setContent {
                HbTheme(darkTheme = isDark, dimensions = HbDimensions.Desktop) {
                    val divider = HbTheme.colors.outline.compositeOver(HbTheme.colors.background)
                    SideEffect { expectedDivider = divider }
                    StudioBrowserLayout(
                        mode,
                        Modifier.background(HbTheme.colors.background).testTag("workspace"),
                        browser = { Box(Modifier.fillMaxSize().testTag("browser-content")) },
                        chat = { Box(Modifier.fillMaxSize().testTag("conversation")) },
                    )
                }
            }
            fun bounds(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            var lightDivider = Color.Unspecified
            for (dark in listOf(false, true)) {
                runOnIdle {
                    isDark = dark
                    mode = BrowserPaneMode.Panel
                }
                val center = bounds("browser-resize").center
                val pixel = captureToImage().toPixelMap()[center.x.toInt(), center.y.toInt()]
                assertColor(expectedDivider, pixel)
                if (dark) assertNotEquals(lightDivider.toArgb(), pixel.toArgb()) else lightDivider = pixel
                runOnIdle { mode = BrowserPaneMode.Hidden }
                onNodeWithTag("browser-content").assertDoesNotExist()
                onNodeWithTag("browser-resize").assertDoesNotExist()
                assertBounds(bounds("workspace"), bounds("conversation"))
            }
        }

    private fun assertBounds(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 1f)
        assertEquals(expected.top, actual.top, 1f)
        assertEquals(expected.right, actual.right, 1f)
        assertEquals(expected.bottom, actual.bottom, 1f)
    }

    private fun assertColor(expected: Color, actual: Color) {
        assertTrue(abs(expected.red - actual.red) < 0.01f)
        assertTrue(abs(expected.green - actual.green) < 0.01f)
        assertTrue(abs(expected.blue - actual.blue) < 0.01f)
    }
}

@Composable
private fun BrowserContent(onMount: () -> Unit, onDispose: () -> Unit) {
    val currentMount by rememberUpdatedState(onMount)
    val currentDispose by rememberUpdatedState(onDispose)
    DisposableEffect(Unit) {
        currentMount()
        onDispose { currentDispose() }
    }
    Box(Modifier.fillMaxSize().testTag("browser-content"))
}
