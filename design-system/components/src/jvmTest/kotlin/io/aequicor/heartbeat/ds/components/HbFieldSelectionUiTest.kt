package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/** The palette owns the selection highlight of editors; kit schemes must not paint their own. */
@OptIn(ExperimentalTestApi::class)
class HbFieldSelectionUiTest {
    @Test
    fun `selected text in a field is painted with the palette highlight in both themes`() {
        listOf(false, true).forEach { dark ->
            runSkikoComposeUiTest(size = Size(420f, 120f)) {
                var highlight = 0
                setContent {
                    HbTheme(darkTheme = dark) {
                        highlight = HbTheme.colors.selectionHighlight.toArgb()
                        Box(
                            Modifier.fillMaxSize().background(HbTheme.colors.background),
                            contentAlignment = Alignment.Center,
                        ) {
                            HbTextField("Selection probe", {}, Modifier.width(240.dp).testTag("field"))
                        }
                    }
                }
                val field = onNodeWithTag("field")
                field.performClick()
                field.assertIsFocused()
                field.performTextInputSelection(TextRange(0, "Selection probe".length))
                waitForIdle()
                val bounds = field.fetchSemanticsNode().boundsInRoot
                val image = captureToImage().toAwtImage()
                val y = bounds.center.y.toInt()
                val painted = (bounds.left.toInt() until bounds.right.toInt())
                    .count { x -> image.getRGB(x, y) == highlight }
                assertTrue(painted > 4, "Selected text must show the palette highlight, found $painted pixels")
            }
        }
    }
}
