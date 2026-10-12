package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.layouts.hbHorizontalScroll
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `desktop backdrop covers the viewport and outside clicks never reach underlying content`() =
        runSkikoComposeUiTest(size = Size(800f, 600f)) {
            var dismissed = 0
            var underlyingClicks = 0
            setContent {
                HbTheme {
                    Box(Modifier.fillMaxSize().clickable { underlyingClicks++ })
                    HbDesktopDialogContent({ dismissed++ }, Modifier.testTag("dialog-host")) {
                        Box(Modifier.size(200.dp, 100.dp).testTag("card"))
                    }
                }
            }
            val host = onNodeWithTag("dialog-host")
            val bounds = host.fetchSemanticsNode().boundsInRoot
            assertEquals(Size(800f, 600f), bounds.size)
            assertEquals(bounds.center, onNodeWithTag("card").fetchSemanticsNode().boundsInRoot.center)
            listOf(Offset(1f, 1f), Offset(799f, 1f), Offset(1f, 599f), Offset(799f, 599f)).forEach { point ->
                host.performMouseInput {
                    moveTo(point)
                    press()
                    release()
                }
            }
            runOnIdle {
                assertEquals(4, dismissed)
                assertEquals(0, underlyingClicks)
            }
            host.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            host.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
        }

    @Test
    fun `blank card space stays modal while its controls keep receiving clicks`() =
        runSkikoComposeUiTest(size = Size(800f, 600f)) {
            var dismissed = 0
            var actions = 0
            setContent {
                HbTheme {
                    HbDesktopDialogContent({ dismissed++ }) {
                        Box(Modifier.size(200.dp, 100.dp).testTag("card")) {
                            HbButton("Apply", { actions++ })
                        }
                    }
                }
            }
            val card = onNodeWithTag("card")
            card.performMouseInput {
                moveTo(Offset(width - 1f, height - 1f))
                press()
                release()
            }
            card.performMouseInput {
                moveTo(Offset(20f, 15f))
                press()
                release()
            }
            runOnIdle {
                assertEquals(0, dismissed)
                assertEquals(1, actions)
            }
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
