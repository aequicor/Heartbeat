package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.test.withKeyDown
import io.aequicor.heartbeat.ds.theme.HbTheme
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbFocusRingUiTest {
    @Test
    fun `window switching chords never reveal the ring of a pointer focused control but typing does`() =
        runSkikoComposeUiTest(size = Size(240f, 120f)) {
            lateinit var focus: FocusRequester
            setContent {
                focus = remember { FocusRequester() }
                HbTheme(darkTheme = false) {
                    Box(
                        Modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).padding(HbTheme.spacing.xl),
                        contentAlignment = Alignment.Center,
                    ) {
                        HbButton("Send", {}, Modifier.focusRequester(focus).testTag("button"))
                    }
                }
            }
            val button = onNodeWithTag("button")
            button.performMouseInput {
                press()
                release()
            }
            runOnIdle { focus.requestFocus() }
            button.assertIsFocused()
            val quiet = captureToImage().toAwtImage()
            // The system takes the Tab of Alt+Tab / Cmd+Tab; the window receives only the modifier.
            button.performKeyInput {
                pressKey(Key.AltLeft)
                pressKey(Key.MetaLeft)
                withKeyDown(Key.CtrlLeft) { pressKey(Key.C) }
            }
            assertTrue(quiet.isSameAs(captureToImage().toAwtImage()), "Window switching keeps the ring hidden")
            button.performKeyInput { pressKey(Key.A) }
            assertFalse(quiet.isSameAs(captureToImage().toAwtImage()), "Keyboard work reveals the ring")
        }
}

private fun BufferedImage.isSameAs(other: BufferedImage): Boolean {
    if (width != other.width || height != other.height) return false
    return (0 until height).all { y -> (0 until width).all { x -> getRGB(x, y) == other.getRGB(x, y) } }
}
