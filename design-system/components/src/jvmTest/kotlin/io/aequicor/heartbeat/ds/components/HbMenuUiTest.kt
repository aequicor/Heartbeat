package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbMenuUiTest {
    @Test
    fun `outside clicks dismiss the menu and never reach the content beneath`() =
        runSkikoComposeUiTest(size = Size(WIDTH, HEIGHT)) {
            val host = MenuHost()
            setMenu(host)
            listOf(Offset(1f, 1f), Offset(WIDTH - 1f, 1f), Offset(1f, HEIGHT - 1f), Offset(WIDTH - 1f, HEIGHT - 1f))
                .forEach { point ->
                    host.isExpanded = true
                    waitForIdle()
                    // The menu layer is the last root: outside the sheet it must cover the whole window.
                    onAllNodes(isRoot()).onLast().performMouseInput {
                        moveTo(point)
                        press()
                        release()
                    }
                    runOnIdle { assertEquals(false, host.isExpanded) }
                }
            runOnIdle {
                assertEquals(4, host.dismissed)
                assertEquals(0, host.underlyingClicks)
                assertEquals(emptyList(), host.selected)
            }
        }

    @Test
    fun `the sheet follows a moving anchor and keeps its blank space modal`() =
        runSkikoComposeUiTest(size = Size(WIDTH, HEIGHT)) {
            val host = MenuHost().apply { isExpanded = true }
            setMenu(host)
            val first = onNodeWithTag(SHEET).fetchSemanticsNode().boundsInRoot
            val anchor = onNodeWithTag(ANCHOR, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(anchor.left, first.left)
            assertTrue(first.top >= anchor.bottom, "The sheet opens below its anchor: $first, $anchor")
            host.anchorOffset = 120
            waitForIdle()
            val moved = onNodeWithTag(SHEET).fetchSemanticsNode().boundsInRoot
            assertEquals(
                onNodeWithTag(ANCHOR, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left,
                moved.left,
            )
            assertTrue(moved.left > first.left, "The sheet must move with its anchor: $first -> $moved")
            onNodeWithTag(SHEET).performMouseInput {
                // The sheet padding is inside the menu, not an outside click.
                moveTo(Offset(width - 1f, 1f))
                press()
                release()
            }
            runOnIdle {
                assertEquals(true, host.isExpanded)
                assertEquals(0, host.dismissed)
            }
            onNodeWithText("Second").performMouseInput {
                moveTo(center)
                press()
                release()
            }
            runOnIdle {
                assertEquals(listOf("second"), host.selected)
                assertEquals(false, host.isExpanded)
                assertEquals(0, host.underlyingClicks)
            }
        }

    @Test
    fun `keyboard focus starts on the first item and Escape dismisses`() =
        runSkikoComposeUiTest(size = Size(WIDTH, HEIGHT)) {
            val host = MenuHost().apply { isExpanded = true }
            setMenu(host)
            onNodeWithText("First").assertIsFocused()
            onNodeWithText("First").performKeyInput { pressKey(Key.DirectionDown) }
            onNodeWithText("Second").assertIsFocused()
            onNodeWithText("Second").performKeyInput { pressKey(Key.Enter) }
            runOnIdle { assertEquals(listOf("second"), host.selected) }
            host.isExpanded = true
            waitForIdle()
            onNodeWithText("First").performKeyInput { pressKey(Key.Escape) }
            runOnIdle {
                assertEquals(false, host.isExpanded)
                // Activation closes the menu first; Escape is the second dismissal.
                assertEquals(2, host.dismissed)
            }
        }

    private fun SkikoComposeUiTest.setMenu(host: MenuHost) = setContent {
        HbTheme {
            Box(Modifier.fillMaxSize().clickable { host.underlyingClicks++ }) {
                Box(Modifier.offset(x = host.anchorOffset.dp, y = 40.dp).size(32.dp).testTag(ANCHOR)) {
                    HbMenu(
                        items = persistentListOf(HbMenuItem("first", "First"), HbMenuItem("second", "Second")),
                        isExpanded = host.isExpanded,
                        onDismiss = {
                            host.dismissed++
                            host.isExpanded = false
                        },
                        onItem = { host.selected = host.selected + it },
                        label = "Actions",
                        modifier = Modifier.testTag(SHEET),
                    )
                }
            }
        }
    }

    private class MenuHost {
        var isExpanded by mutableStateOf(false)
        var anchorOffset by mutableIntStateOf(40)
        var dismissed = 0
        var underlyingClicks = 0
        var selected = emptyList<String>()
    }

    private companion object {
        const val WIDTH = 800f
        const val HEIGHT = 600f
        const val ANCHOR = "anchor"
        const val SHEET = "sheet"
    }
}
