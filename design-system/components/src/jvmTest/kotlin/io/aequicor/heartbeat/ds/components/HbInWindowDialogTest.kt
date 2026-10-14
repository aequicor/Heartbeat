package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HbInWindowDialogTest {
    @Test
    fun `Tab reaches the sole document Close action and Enter restores the opener`() = closesDocument(Key.Enter)

    @Test
    fun `Tab reaches the sole document Close action and Space restores the opener`() = closesDocument(Key.Spacebar)

    private fun closesDocument(activation: Key) = runSkikoComposeUiTest {
        val dialogs = InWindowDialogs()
        var isOpen by mutableStateOf(false)
        setContent {
            val opener = remember { FocusRequester() }
            HbTheme {
                HbInWindowDialogPortal(dialogs) {
                    Column(Modifier.focusRestorer().focusGroup()) {
                        HbButton("First", {}, Modifier.testTag("first"))
                        HbButton(
                            "Open document",
                            {
                                opener.requestFocus()
                                isOpen = true
                            },
                            Modifier.focusRequester(opener).testTag("opener"),
                        )
                    }
                    if (isOpen) {
                        HbDialog(
                            "Document",
                            onDismissRequest = { isOpen = false },
                            modifier = Modifier.testTag("modal"),
                            isContentScrollable = false,
                            isContentEdgeToEdge = true,
                            overlayCloseLabel = "Close document",
                        ) {
                            Box(Modifier.fillMaxWidth().height(180.dp).testTag("page"))
                        }
                    }
                }
            }
        }
        onNodeWithTag("opener").performMouseInput { click() }
        onNodeWithTag("modal").assertIsFocused()
        assertEquals(1, dialogs.entries.size)
        onAllNodesWithContentDescription("Close document", useUnmergedTree = true).assertCountEquals(1)
        onNodeWithTag("modal").performKeyInput { pressKey(Key.Tab) }
        onNodeWithContentDescription("Close document").assertIsFocused().performKeyInput { pressKey(activation) }
        onNodeWithTag("modal").assertDoesNotExist()
        assertEquals(0, dialogs.entries.size)
        onNodeWithTag("opener").assertIsFocused()
    }

    @Test
    fun `a pointer opener regains focus after the in-window dialog closes and reopens from the keyboard`() =
        restoresOpener(isPointerClick = true)

    @Test
    fun `an accessibility opener regains focus after the in-window dialog closes and reopens from the keyboard`() =
        restoresOpener(isPointerClick = false)

    private fun restoresOpener(isPointerClick: Boolean) = runSkikoComposeUiTest {
        val dialogs = InWindowDialogs()
        var isOpen by mutableStateOf(false)
        setContent {
            val opener = remember { FocusRequester() }
            val scope = rememberCoroutineScope()
            HbTheme {
                HbInWindowDialogPortal(dialogs) {
                    // Studio's shortcut receiver is itself focusable above each pane's focus group.
                    Box(Modifier.focusRestorer().focusable().testTag("workspace")) {
                        Column(Modifier.focusRestorer().focusGroup()) {
                            HbButton("First", {}, Modifier.testTag("first"))
                            HbIconButton(
                                HbIcons.Sidebar,
                                "Context",
                                {
                                    opener.requestFocus()
                                    isOpen = true
                                },
                                Modifier.focusRequester(opener).testTag("opener"),
                            )
                        }
                    }
                    if (isOpen) {
                        HbDialog(
                            "Context",
                            onDismissRequest = {
                                isOpen = false
                                scope.launch {
                                    withFrameNanos { }
                                    opener.requestFocus()
                                }
                            },
                            modifier = Modifier.testTag("modal"),
                            isInWindow = true,
                        ) {
                            HbButton("Action", {}, Modifier.testTag("action"))
                        }
                    }
                }
            }
        }
        if (isPointerClick) {
            onNodeWithTag("opener").performMouseInput { click() }
        } else {
            onNodeWithTag("opener").performClick()
        }
        onNodeWithTag("modal").assertIsFocused()
        assertEquals(1, dialogs.entries.size, "The actual in-window portal must own the modal")
        for (key in listOf(Key.Enter, Key.Spacebar)) {
            onNodeWithTag("action").performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag("modal").assertDoesNotExist()
            assertEquals(0, dialogs.entries.size)
            onNodeWithTag("opener").assertIsFocused().performKeyInput { pressKey(key) }
            onNodeWithTag("modal").assertIsDisplayed().assertIsFocused()
            assertEquals(1, dialogs.entries.size)
        }
    }
}
