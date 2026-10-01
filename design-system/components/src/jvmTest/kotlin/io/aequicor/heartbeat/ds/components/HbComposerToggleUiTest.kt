package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** The composer mode switch announces its state and reports every change to its owner. */
@OptIn(ExperimentalTestApi::class)
class HbComposerToggleUiTest {
    @Test
    fun `toggle shows its full label and flips its checked state`() = runSkikoComposeUiTest(size = Size(480f, 120f)) {
        var isChecked by mutableStateOf(false)
        val changes = mutableListOf<Boolean>()
        setContent {
            HbTheme(darkTheme = false) {
                HbComposerToggle(
                    label = "Исследование",
                    isChecked = isChecked,
                    onCheckedChange = {
                        changes += it
                        isChecked = it
                    },
                    modifier = Modifier.testTag("toggle"),
                    icon = HbIcons.Library,
                )
            }
        }
        onNodeWithText("Исследование").assertExists()
        onNodeWithTag("toggle").assertIsOff().performClick()
        onNodeWithTag("toggle").assertIsOn().performClick()
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun `checked toggle wears the composer accent tint while an unchecked one stays quiet`() {
        for (dark in listOf(false, true)) {
            runSkikoComposeUiTest(size = Size(480f, 120f)) {
                var accent = 0
                var quiet = 0
                setContent {
                    HbTheme(darkTheme = dark) {
                        accent = HbTheme.surfaces.composerPillAccent.toArgb()
                        quiet = HbTheme.surfaces.composerPill.toArgb()
                        HbRow(gap = HbTheme.spacing.m) {
                            HbComposerToggle("Worktree", true, {}, Modifier.testTag("checked"), icon = HbIcons.Branch)
                            HbComposerToggle("Research", false, {}, Modifier.testTag("unchecked"))
                        }
                    }
                }
                val image = captureToImage().toAwtImage()
                val checked = onNodeWithTag("checked").fetchSemanticsNode().boundsInRoot
                val unchecked = onNodeWithTag("unchecked").fetchSemanticsNode().boundsInRoot
                assertEquals(accent, image.getRGB((checked.left + 2).toInt(), checked.center.y.toInt()))
                assertEquals(quiet, image.getRGB((unchecked.left + 2).toInt(), unchecked.center.y.toInt()))
            }
        }
    }
}
