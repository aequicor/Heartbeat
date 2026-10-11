package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HbComposerSuggestionsUiTest {
    @Test
    fun `enter accepts the selected suggestion instead of sending`() = runSkikoComposeUiTest(size = Size(1200f, 700f)) {
        var draft by mutableStateOf("")
        var sends by mutableIntStateOf(0)
        setContent {
            SuggestionsComposer(
                draft = draft,
                onDraft = { draft = it },
                onSend = { sends++ },
                suggestions = sampleSuggestions(),
                onAccept = { index -> draft = "chosen:$index" },
            )
        }
        val editor = onNodeWithContentDescription("Prompt")
        editor.performMouseInput { click() }
        editor.assertIsFocused()
        onNodeWithText("Remember a lesson").assertExists()
        editor.performKeyInput { pressKey(Key.Enter) }
        runOnIdle {
            assertEquals("chosen:0", draft)
            assertEquals(0, sends)
        }
    }

    @Test
    fun `arrows move the selection and tab takes the moved one`() = runSkikoComposeUiTest(size = Size(1200f, 700f)) {
        var accepted by mutableIntStateOf(-1)
        var selectedIndex by mutableIntStateOf(0)
        setContent {
            val suggestions = sampleSuggestions(selectedIndex)
            SuggestionsComposer(
                draft = "/",
                onDraft = {},
                onSend = {},
                suggestions = suggestions,
                onMove = { delta ->
                    selectedIndex = (selectedIndex + delta).mod(suggestions.items.size)
                },
                onAccept = { accepted = it },
            )
        }
        val editor = onNodeWithContentDescription("Prompt")
        editor.performMouseInput { click() }
        editor.performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Tab)
        }
        runOnIdle { assertEquals(1, accepted) }
    }

    @Test
    fun `escape dismisses the list and restores sending`() = runSkikoComposeUiTest(size = Size(1200f, 700f)) {
        var draft by mutableStateOf("/")
        var sends by mutableIntStateOf(0)
        var suggestions by mutableStateOf<HbComposerSuggestions?>(sampleSuggestions())
        setContent {
            SuggestionsComposer(
                draft = draft,
                onDraft = { draft = it },
                onSend = { sends++ },
                suggestions = suggestions,
                onDismiss = { suggestions = null },
                onAccept = {},
            )
        }
        val editor = onNodeWithContentDescription("Prompt")
        editor.performMouseInput { click() }
        editor.performKeyInput { pressKey(Key.Escape) }
        runOnIdle { assertEquals(0, sends) }
        onNodeWithText("Remember a lesson").assertDoesNotExist()
        editor.performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(1, sends) }
    }

    @Test
    fun `caret changes are reported for token triggers`() = runSkikoComposeUiTest(size = Size(1200f, 700f)) {
        var draft by mutableStateOf("")
        var caret by mutableIntStateOf(-1)
        setContent { SuggestionsComposer(draft, { draft = it }, {}, null, onCaret = { caret = it }, onAccept = {}) }
        val editor = onNodeWithContentDescription("Prompt")
        editor.performMouseInput { click() }
        typeAwtText("ab")
        runOnIdle {
            assertEquals("ab", draft)
            assertEquals(2, caret)
        }
    }

    @Test
    fun `clicking a suggestion takes it and keeps the draft unsent`() =
        runSkikoComposeUiTest(size = Size(1200f, 700f)) {
            var draft by mutableStateOf("@")
            var sends by mutableIntStateOf(0)
            setContent {
                SuggestionsComposer(
                    draft = draft,
                    onDraft = { draft = it },
                    onSend = { sends++ },
                    suggestions = sampleSuggestions(),
                    onAccept = { index -> draft = "clicked:$index" },
                )
            }
            onNodeWithContentDescription("Prompt").performMouseInput { click() }
            onNodeWithText("Read UTF-8 files").performMouseInput { click() }
            runOnIdle {
                assertEquals("clicked:2", draft)
                assertEquals(0, sends)
            }
        }
}

/** Desktop text handling requires AWT KEY_TYPED; pressKey injects only non-text down/up events. */
@OptIn(ExperimentalTestApi::class, androidx.compose.ui.InternalComposeUiApi::class)
private fun SkikoComposeUiTest.typeAwtText(text: String) {
    runOnUiThread {
        val source = java.awt.Canvas()
        text.forEach { character ->
            val native = java.awt.event.KeyEvent(
                source,
                java.awt.event.KeyEvent.KEY_TYPED,
                0,
                0,
                java.awt.event.KeyEvent.VK_UNDEFINED,
                character,
            )
            scene.sendKeyEvent(
                androidx.compose.ui.input.key.KeyEvent(
                    Key.Unknown,
                    androidx.compose.ui.input.key.KeyEventType.Unknown,
                    codePoint = character.code,
                    nativeEvent = native,
                ),
            )
        }
    }
    waitForIdle()
}

@Composable
private fun SuggestionsComposer(
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    suggestions: HbComposerSuggestions?,
    onAccept: (Int) -> Unit,
    onCaret: (Int) -> Unit = {},
    onMove: (Int) -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    HbTheme {
        Box(Modifier.fillMaxSize().background(HbTheme.colors.background)) {
            HbColumn(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                HbChatComposer(
                    value = draft,
                    onValueChange = onDraft,
                    onSend = onSend,
                    onStop = {},
                    sendLabel = "Send",
                    stopLabel = "Stop",
                    layout = HbComposerLayout.Panel,
                    placeholder = "Prompt",
                    suggestions = suggestions,
                    suggestionsLabel = "Suggestions",
                    onSuggestionMove = onMove,
                    onSuggestionAccept = onAccept,
                    onSuggestionsDismiss = onDismiss,
                    onCaretChange = onCaret,
                )
            }
        }
    }
}

private fun sampleSuggestions(selectedIndex: Int = 0): HbComposerSuggestions = HbComposerSuggestions(
    items = persistentListOf(
        HbComposerSuggestion("remember", "Remember a lesson", "Save a durable instruction", "Commands"),
        HbComposerSuggestion("verify", "Verify the change", "Run applicable checks", null),
        HbComposerSuggestion("utf8", "Read UTF-8 files", "Encoding helper", "Skills"),
    ),
    selectedIndex = selectedIndex,
)
