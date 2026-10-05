package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.layouts.hbHorizontalScroll
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbDialogUiTest {
    @Test
    fun `a wide dialog grows past the regular width bound`() {
        val regular = bodyWidth(HbDialogWidth.Regular)
        val wide = bodyWidth(HbDialogWidth.Wide)
        assertTrue(regular < HbDimensions.Desktop.dialogMaxWidth.value, "regular $regular")
        assertTrue(wide > HbDimensions.Desktop.dialogMaxWidth.value, "wide $wide")
    }

    @Test
    fun `content that scrolls on both axes stays bounded by the window`() = runSkikoComposeUiTest(
        size = Size(WINDOW_WIDTH, WINDOW_HEIGHT),
    ) {
        setContent {
            HbTheme {
                HbDialog("Diagram", onDismissRequest = {}, width = HbDialogWidth.Wide, isContentScrollable = false) {
                    Box(
                        Modifier.testTag("viewport")
                            .hbVerticalScroll(rememberScrollState())
                            .hbHorizontalScroll(rememberScrollState()),
                    ) { Box(Modifier.size(3000.dp)) }
                }
            }
        }
        val viewport = onNodeWithTag("viewport").getBoundsInRoot()
        assertTrue((viewport.bottom - viewport.top).value < WINDOW_HEIGHT)
        assertTrue((viewport.right - viewport.left).value < WINDOW_WIDTH)
    }

    private fun bodyWidth(width: HbDialogWidth): Float {
        var result = 0f
        runSkikoComposeUiTest(size = Size(WINDOW_WIDTH, WINDOW_HEIGHT)) {
            setContent {
                HbTheme {
                    HbDialog("Dialog", onDismissRequest = {}, width = width) {
                        Box(Modifier.testTag("body").fillMaxWidth().height(10.dp))
                    }
                }
            }
            val bounds = onNodeWithTag("body").getBoundsInRoot()
            result = (bounds.right - bounds.left).value
        }
        return result
    }

    private companion object {
        const val WINDOW_WIDTH = 1400f
        const val WINDOW_HEIGHT = 900f
    }
}
