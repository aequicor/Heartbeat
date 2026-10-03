package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbWindowChromeUiTest {
    @Test
    fun `caption icon hover is client area before the first press and free caption remains draggable`() =
        runSkikoComposeUiTest(size = Size(500f, 200f)) {
            val hitTests = mutableListOf<Boolean>()
            var clicks = 0
            setContent {
                HbTheme(dimensions = HbDimensions.Desktop) {
                    HbWindowChromeProvider(
                        HbWindowChrome(height = 44.dp),
                        Modifier.fillMaxSize().testTag("window"),
                        onNativeHitTest = { hitTests += it },
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            HbPaneHeader(
                                "Title",
                                navigation = { HbIconButton(HbIcons.ArrowLeft, "Back", { clicks++ }) },
                            )
                        }
                    }
                }
            }
            onNodeWithTag("window").performMouseInput { moveTo(Offset(200f, 20f)) }
            assertEquals(false, hitTests.last())
            val button = onNodeWithContentDescription("Back")
            button.performMouseInput { moveTo(center) }
            assertEquals(true, hitTests.last(), "The native caption must know this is a control before mouse-down")
            hitTests.clear()
            button.performMouseInput {
                press()
                moveBy(Offset(1f, 0f))
                release()
            }
            assertEquals(1, clicks)
            assertTrue(hitTests.all { it })
            onNodeWithTag("window").performMouseInput { moveTo(Offset(200f, 20f)) }
            assertEquals(false, hitTests.last(), "Leaving a control must restore native dragging")
        }

    @Test
    fun `a header without a window provider preserves its full width`() =
        runSkikoComposeUiTest(size = Size(420f, 200f)) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    HbTheme(dimensions = HbDimensions.Desktop) {
                        HbWindowDragArea(Modifier.fillMaxWidth().height(44.dp)) {
                            Box(Modifier.fillMaxSize().testTag("standalone-header"))
                        }
                    }
                }
            }
            assertEquals(420f, onNodeWithTag("standalone-header").fetchSemanticsNode().boundsInRoot.width)
        }

    @Test
    fun `caption excludes native controls and removes insets when fullscreen changes`() =
        runSkikoComposeUiTest(size = Size(420f, 200f)) {
            var isFullscreen by mutableStateOf(false)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    HbTheme(dimensions = HbDimensions.Desktop) {
                        HbWindowChromeProvider(
                            HbWindowChrome(44.dp, 96.dp, 138.dp, isFullscreen),
                            Modifier.fillMaxSize(),
                        ) {
                            HbWindowDragArea(Modifier.fillMaxWidth().height(44.dp)) {
                                Box(Modifier.fillMaxSize().testTag("safe-header"))
                            }
                        }
                    }
                }
            }
            waitForIdle()
            val caption = onNodeWithTag("safe-header").fetchSemanticsNode().boundsInRoot
            assertEquals(96f, caption.left)
            assertEquals(282f, caption.right)
            isFullscreen = true
            waitForIdle()
            assertEquals(420f, onNodeWithTag("safe-header").fetchSemanticsNode().boundsInRoot.width)
        }

    @Test
    fun `empty caption delegates to native actions but buttons remain Compose client controls`() =
        runSkikoComposeUiTest(size = Size(500f, 200f)) {
            val hitTests = mutableListOf<Boolean>()
            var clicks = 0
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    HbTheme(dimensions = HbDimensions.Desktop) {
                        HbWindowChromeProvider(
                            HbWindowChrome(height = 44.dp),
                            Modifier.fillMaxSize().testTag("window"),
                            onNativeHitTest = { hitTests += it },
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                HbPaneHeader("Title", actions = { HbButton("Action", { clicks++ }) })
                            }
                        }
                    }
                }
            }
            onNodeWithTag("window").performMouseInput {
                moveTo(Offset(200f, 20f))
                press()
            }
            assertEquals(false, hitTests.last(), "Free header must retain native drag and double-click")
            onNodeWithTag("window").performMouseInput { release() }
            hitTests.clear()
            onNodeWithText("Action").performMouseInput {
                moveTo(center)
                press()
            }
            assertEquals(true, hitTests.last(), "A pressed button must never start moving the window")
            onNodeWithText("Action").performMouseInput {
                moveBy(Offset(1f, 1f))
                release()
            }
            assertEquals(1, clicks)
            assertTrue(
                hitTests.all { it },
                "A client press must keep movement and release out of native caption handling",
            )
            onNodeWithTag("window").performMouseInput { moveTo(Offset(200f, 100f)) }
            assertTrue(hitTests.last(), "Content below the caption is client area")
        }
}
