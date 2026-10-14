package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class HbDialogOverlayUiTest {
    @Test
    fun `edge content keeps only the outer window margin and reports the measured close corner`() =
        runSkikoComposeUiTest(size = Size(1000f, 700f)) {
            var seen = HbDialogOverlayInsets()
            var margin = 0f
            var inset = 0f
            var height by mutableIntStateOf(180)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    HbTheme(dimensions = HbDimensions.Desktop) {
                        val spacing = HbTheme.spacing
                        SideEffect {
                            margin = spacing.l.value
                            inset = spacing.s.value
                        }
                        HbDialog(
                            "Web app",
                            {},
                            Modifier.testTag("dialog"),
                            isContentScrollable = false,
                            isContentEdgeToEdge = true,
                            headerActions = { HbIconButton(HbIcons.Close, "Close", {}) },
                        ) {
                            val occupied = LocalHbDialogOverlayInsets.current
                            SideEffect { seen = occupied }
                            Box(Modifier.fillMaxWidth().height(height.dp).testTag("page"))
                        }
                    }
                }
            }
            val page = onNodeWithTag("page").fetchSemanticsNode().boundsInRoot
            val dialog = onNodeWithTag("dialog").fetchSemanticsNode().boundsInRoot
            val close = onNodeWithContentDescription("Close").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(180f, page.height)
            assertEquals(page.height + margin * 2, dialog.height)
            assertEquals(margin, page.left - dialog.left)
            assertEquals(margin, dialog.right - page.right)
            assertEquals(close.width + inset, seen.width.value)
            assertEquals(close.height + inset, seen.height.value)
            onNodeWithText("Web app").assertDoesNotExist()
            runOnIdle { height = 90 }
            assertEquals(90f, onNodeWithTag("page").fetchSemanticsNode().boundsInRoot.height)
            onNodeWithContentDescription("Close").assertIsDisplayed()
        }

    @Test
    fun `the small overlay lets the page handle clicks and its close action still works`() =
        runSkikoComposeUiTest(size = Size(1000f, 700f)) {
            var pageClicks = 0
            var dismissed = 0
            setContent {
                HbTheme(dimensions = HbDimensions.Desktop) {
                    HbDialog(
                        "Web app",
                        { dismissed++ },
                        isContentScrollable = false,
                        isContentEdgeToEdge = true,
                        headerActions = { HbIconButton(HbIcons.Close, "Close", { dismissed++ }) },
                    ) {
                        Box(Modifier.fillMaxWidth().height(240.dp).testTag("page").clickable { pageClicks++ })
                    }
                }
            }
            onNodeWithTag("page").performMouseInput {
                moveTo(center)
                press()
                release()
            }
            runOnIdle {
                assertEquals(1, pageClicks)
                assertEquals(0, dismissed)
            }
            onNodeWithContentDescription("Close").assertIsDisplayed().performClick()
            assertEquals(1, dismissed)
        }

    @Test
    fun `a standard dialog retains its header and padding and clears inherited overlay space`() =
        runSkikoComposeUiTest(size = Size(1000f, 700f)) {
            var seen = HbDialogOverlayInsets(100.dp, 100.dp)
            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                    LocalHbDialogOverlayInsets provides HbDialogOverlayInsets(100.dp, 100.dp),
                ) {
                    HbTheme(dimensions = HbDimensions.Desktop) {
                        HbDialog("Ordinary dialog", {}, Modifier.testTag("dialog")) {
                            val occupied = LocalHbDialogOverlayInsets.current
                            SideEffect { seen = occupied }
                            Box(Modifier.fillMaxWidth().height(180.dp).testTag("page"))
                        }
                    }
                }
            }
            onNodeWithText("Ordinary dialog").assertIsDisplayed()
            val page = onNodeWithTag("page").fetchSemanticsNode().boundsInRoot
            val dialog = onNodeWithTag("dialog").fetchSemanticsNode().boundsInRoot
            assertEquals(180f, page.height)
            assertTrue(dialog.height > page.height + 50f)
            assertTrue(page.left - dialog.left > 20f)
            assertEquals(HbDialogOverlayInsets(), seen)
        }
}
