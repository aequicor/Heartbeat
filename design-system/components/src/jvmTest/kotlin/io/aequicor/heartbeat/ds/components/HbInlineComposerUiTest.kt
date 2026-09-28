package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbInlineComposerUiTest {
    @Test
    fun `inline primary action shares the editor row and remains reachable with long draft and toolbar`() =
        runSkikoComposeUiTest(size = Size(320f, 360f)) {
            var isStreaming by mutableStateOf(false)
            var sends = 0
            var stops = 0
            val draft = "Long draft line\n".repeat(100)
            setContent {
                HbTheme(darkTheme = false, dimensions = HbDimensions()) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
                        HbChatComposer(
                            value = draft,
                            onValueChange = {},
                            onSend = { sends++ },
                            onStop = { stops++ },
                            sendLabel = "Send",
                            stopLabel = "Stop",
                            modifier = Modifier.testTag("composer"),
                            inputMaxHeight = 64.dp,
                            layout = HbComposerLayout.Inline,
                            placeholder = "Prompt",
                            isStreaming = isStreaming,
                            leadingContent = { HbButton("Add", {}, style = HbButtonStyle.Quiet) },
                            trailingContent = {
                                HbButton("A deliberately long model name", {}, style = HbButtonStyle.Quiet)
                                HbButton("Reasoning mode", {}, style = HbButtonStyle.Quiet)
                            },
                        )
                    }
                }
            }
            val editor = onNodeWithContentDescription("Prompt")
            val editorBounds = editor.fetchSemanticsNode().boundsInRoot
            val actionBounds = onNodeWithContentDescription("Send")
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val toolbarBounds = onNodeWithText("Add").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(editorBounds.height <= 64f)
            assertEquals(48f, actionBounds.width)
            assertEquals(48f, actionBounds.height)
            assertTrue(actionBounds.left >= editorBounds.right)
            assertTrue(actionBounds.center.y in editorBounds.top..editorBounds.bottom)
            assertTrue(toolbarBounds.top >= maxOf(editorBounds.bottom, actionBounds.bottom))
            editor.performSemanticsAction(SemanticsActions.RequestFocus)
            editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
            runOnIdle { assertEquals(1, sends) }
            editor.performKeyInput { pressKey(Key.Tab) }
            onNodeWithContentDescription("Send").assertIsFocused().performClick()
            runOnIdle {
                assertEquals(2, sends)
                isStreaming = true
            }
            onNodeWithContentDescription("Send").assertDoesNotExist()
            onNodeWithContentDescription("Stop").assertIsDisplayed().performClick()
            runOnIdle { assertEquals(1, stops) }
        }

    @Test
    fun `blank inline editor stays compact and its menu opens above composer with focus restored on Escape`() =
        runSkikoComposeUiTest(size = Size(620f, 700f)) {
            var isExpanded by mutableStateOf(false)
            setContent {
                HbTheme(darkTheme = true) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
                        HbChatComposer(
                            "", {}, {}, {}, "Send", "Stop",
                            modifier = Modifier.testTag("composer"),
                            layout = HbComposerLayout.Inline,
                            placeholder = "Prompt",
                            leadingContent = {
                                HbComposerMenuButton(
                                    label = "Add",
                                    actions = persistentListOf(HbComposerAction("files", "Files")),
                                    isExpanded = isExpanded,
                                    onExpandedChange = { isExpanded = it },
                                    onAction = {},
                                    icon = HbIcons.Plus,
                                )
                            },
                        )
                    }
                }
            }
            val composerBounds = onNodeWithTag("composer").fetchSemanticsNode().boundsInWindow
            val editorBounds = onNodeWithContentDescription("Prompt").fetchSemanticsNode().boundsInRoot
            assertEquals(32f, editorBounds.height)
            assertTrue(composerBounds.height <= 112f)
            onNodeWithContentDescription("Add").performClick()
            val menuBounds = onNodeWithText("Files").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
            assertTrue(menuBounds.bottom <= composerBounds.top)
            onNodeWithText("Files").performKeyInput { pressKey(Key.Escape) }
            onNodeWithText("Files").assertDoesNotExist()
            onNodeWithContentDescription("Add").assertIsFocused()
        }
}
