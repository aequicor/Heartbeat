package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
class HbBusyControlsUiTest {
    @Test
    fun `pending controls reject input immediately and only long writes change their palette`() =
        runSkikoComposeUiTest(size = Size(320f, 200f)) {
            var isBusy by mutableStateOf(false)
            var changes = 0
            val tags = listOf("button", "switch")
            setContent { BusyControls(isBusy, onChange = { changes++ }) }
            val idle = tags.associateWith { controlPixel(it) }
            onNodeWithTag("button").performSemanticsAction(SemanticsActions.RequestFocus)
            onNodeWithTag("button").assertIsFocused()
            mainClock.autoAdvance = false
            runOnIdle { isBusy = true }
            mainClock.advanceTimeByFrame()
            onNodeWithTag("button").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
            tags.forEach { tag ->
                onNodeWithTag(tag).assertIsNotEnabled()
                assertEquals(idle.getValue(tag), controlPixel(tag), "$tag must not flash at write start")
                onNodeWithTag(tag).performMouseInput {
                    moveTo(center)
                    press()
                    release()
                    exit()
                }
                onNodeWithTag(tag).performKeyInput { pressKey(Key.Enter) }
                onNodeWithTag(tag).performKeyInput { pressKey(Key.Spacebar) }
            }
            runOnIdle { assertEquals(0, changes) }
            runOnIdle { isBusy = false }
            mainClock.advanceTimeBy(HbMotion().busyRevealDelayMillis.toLong() * 2)
            tags.forEach { tag ->
                onNodeWithTag(tag).assertIsEnabled()
                assertEquals(idle.getValue(tag), controlPixel(tag), "$tag must not flash after a short write")
            }
            runOnIdle { isBusy = true }
            mainClock.advanceTimeBy(HbMotion().busyRevealDelayMillis.toLong() * 2)
            tags.forEach { tag ->
                onNodeWithTag(tag).assertIsNotEnabled()
                assertNotEquals(idle.getValue(tag), controlPixel(tag), "$tag must dim during a long write")
            }
            runOnIdle { isBusy = false }
            mainClock.advanceTimeByFrame()
            tags.forEach { tag ->
                onNodeWithTag(tag).assertIsEnabled()
                assertEquals(idle.getValue(tag), controlPixel(tag), "$tag must restore its palette immediately")
                onNodeWithTag(tag).performMouseInput {
                    moveTo(center)
                    press()
                    release()
                    exit()
                }
            }
            runOnIdle { assertEquals(2, changes) }
        }

    private fun SkikoComposeUiTest.controlPixel(tag: String): Int {
        val bounds = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val y = if (tag == "switch") bounds.center.y else bounds.top + bounds.height / 4f
        return captureToImage().toAwtImage().getRGB(bounds.center.x.toInt(), y.toInt())
    }
}

/** Both primitive controls expose the raw availability while delaying only the busy palette. */
@Composable
private fun BusyControls(isBusy: Boolean, onChange: () -> Unit) {
    HbTheme(darkTheme = false) {
        val isVisible = rememberHbBusyReveal(isBusy)
        Column {
            HbButton(
                "Save",
                onChange,
                Modifier.testTag("button"),
                enabled = !isBusy,
                visualEnabled = !isVisible,
            )
            HbSwitch(
                true,
                { onChange() },
                "Enabled",
                Modifier.testTag("switch"),
                enabled = !isBusy,
                visualEnabled = !isVisible,
            )
        }
    }
}
