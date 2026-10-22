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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbComposerSuggestionsUiTest {

    @Test
    fun `extending a skill name at the visible end detaches its old target`() = runSkikoComposeUiTest {
        var draft by mutableStateOf("[/open-pr](.agents/skills/open-pr/SKILL.md) tail")
        setContent { SuggestionsComposer(draft, { draft = it }, {}, null, {}) }
        val editor = onNodeWithContentDescription("Prompt")
        editor.performTextInputSelection(TextRange(draft.length))
        editor.performKeyInput { repeat(5) { pressKey(Key.DirectionLeft) } }
        typeAwtText("X")
        runOnIdle { assertEquals("/open-prX tail", draft) }
    }

    @Test
    fun `backspace at a visible skill boundary leaves plain text without markdown debris`() = runSkikoComposeUiTest {
        var draft by mutableStateOf("[/open-pr](.agents/skills/open-pr/SKILL.md) tail")
        setContent { SuggestionsComposer(draft, { draft = it }, {}, null, {}) }
        val editor = onNodeWithContentDescription("Prompt")
        editor.performTextInputSelection(TextRange(draft.length))
        editor.performKeyInput {
            repeat(5) { pressKey(Key.DirectionLeft) }
            pressKey(Key.Backspace)
        }
        runOnIdle { assertEquals("/open-p tail", draft) }
    }

    @Test
    fun `editing a skill label detaches the previous instruction path`() = runSkikoComposeUiTest {
        var draft by mutableStateOf("[/open-pr](.agents/skills/open-pr/SKILL.md) tail")
        setContent { SuggestionsComposer(draft, { draft = it }, {}, null, {}) }
        val editor = onNodeWithContentDescription("Prompt")
        editor.performTextInputSelection(TextRange(draft.length))
        editor.performKeyInput { repeat(7) { pressKey(Key.DirectionLeft) } }
        typeAwtText("new-")
        runOnIdle { assertEquals("/open-new-pr tail", draft) }
    }

    @Test
    fun `forward delete at the start of a skill detaches the path`() = runSkikoComposeUiTest {
        var draft by mutableStateOf("[/open-pr](.agents/skills/open-pr/SKILL.md) tail")
        setContent { SuggestionsComposer(draft, { draft = it }, {}, null, {}) }
        val editor = onNodeWithContentDescription("Prompt")
        editor.performTextInputSelection(TextRange(0))
        editor.performKeyInput { pressKey(Key.Delete) }
        runOnIdle { assertEquals("open-pr tail", draft) }
    }

    @Test
    fun `skill reference is a coloured label and keeps its target when submitting`() {
        for (width in listOf(420f, 1280f)) {
            for (dark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width, 700f)) {
                    val raw = "[/open-pr](.agents/skills/open-pr/SKILL.md) @read_file "
                    var draft by mutableStateOf(raw)
                    var sent = ""
                    setContent {
                        SuggestionsComposer(draft, { draft = it }, { sent = draft }, null, {}, darkTheme = dark)
                    }
                    val editor = onNodeWithContentDescription("Prompt")
                    val layouts = mutableListOf<TextLayoutResult>()
                    editor.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    val displayed = layouts.last().layoutInput.text
                    assertEquals("/open-pr @read_file ", displayed.text)
                    assertEquals(listOf(0, 9), displayed.spanStyles.map { it.start })
                    editor.performTextInputSelection(TextRange(raw.length))
                    typeAwtText("next")
                    runOnIdle { assertEquals(raw + "next", draft) }
                    editor.performKeyInput { pressKey(Key.Enter) }
                    runOnIdle { assertEquals(raw + "next", sent) }
                    val directory = File("build/composer-suggestions-ui").apply { mkdirs() }
                    check(
                        ImageIO.write(
                            captureToImage().toAwtImage(),
                            "png",
                            File(directory, "skill-reference-${width.toInt()}-$dark.png"),
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `selecting a whole inline skill removes its hidden target too`() = runSkikoComposeUiTest {
        var draft by mutableStateOf("[/open-pr](.agents/skills/open-pr/SKILL.md) tail")
        setContent { SuggestionsComposer(draft, { draft = it }, {}, null, {}) }
        val editor = onNodeWithContentDescription("Prompt")
        editor.performTextInputSelection(TextRange(0, draft.indexOf(" tail")))
        editor.performKeyInput { pressKey(Key.Backspace) }
        runOnIdle { assertEquals(" tail", draft) }
    }

    @Test
    fun `single section heading shares the close row without a blank header`() =
        runSkikoComposeUiTest(size = Size(420f, 700f)) {
            setContent {
                SuggestionsComposer(
                    "@br",
                    {},
                    {},
                    HbComposerSuggestions(
                        persistentListOf(HbComposerSuggestion("browser", "@browser", sectionLabel = "Commands")),
                    ),
                    onAccept = {},
                )
            }
            onNodeWithContentDescription("Prompt").performMouseInput { click() }
            val heading = onNodeWithText("Commands").fetchSemanticsNode().boundsInWindow
            val close = onNodeWithContentDescription("Close suggestions").fetchSemanticsNode().boundsInWindow
            val row = onNodeWithText("@browser").fetchSemanticsNode().boundsInWindow
            assertTrue(heading.center.y in close.top..close.bottom)
            assertTrue(row.top >= heading.bottom)
        }

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
        onNodeWithText("Suggestions").assertDoesNotExist()
        val heading = onNodeWithText("Commands").fetchSemanticsNode().boundsInWindow
        val close = onNodeWithContentDescription("Close suggestions").fetchSemanticsNode().boundsInWindow
        assertTrue(heading.center.y in close.top..close.bottom)
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
    fun `completion moves the caret after insertion in the middle of a draft only once`() =
        runSkikoComposeUiTest(size = Size(1200f, 700f)) {
            var draft by mutableStateOf("/re tail")
            var request by mutableStateOf<HbComposerCaretRequest?>(null)
            var suggestions by mutableStateOf<HbComposerSuggestions?>(sampleSuggestions())
            val appliedRequests = mutableListOf<Long>()
            setContent {
                SuggestionsComposer(
                    draft,
                    { draft = it },
                    {},
                    suggestions,
                    onAccept = {
                        draft = "/remember  tail"
                        request = HbComposerCaretRequest(1, draft, 10)
                        suggestions = null
                    },
                    caretRequest = request,
                    onCaretRequestApply = { appliedRequests += it },
                )
            }
            val editor = onNodeWithContentDescription("Prompt")
            editor.performTextInputSelection(TextRange(3))
            editor.performKeyInput { pressKey(Key.Enter) }
            assertEquals(TextRange(10), editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            editor.performTextInputSelection(TextRange(0))
            assertEquals(TextRange(0), editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            editor.performTextInputSelection(TextRange(10))
            typeAwtText("X")
            runOnIdle { assertEquals("/remember X tail", draft) }
            // The retained request must not reset a later explicit user caret move.
            editor.performTextInputSelection(TextRange(0))
            typeAwtText("Y")
            runOnIdle { assertEquals("Y/remember X tail", draft) }
            runOnIdle { assertEquals(listOf(1L), appliedRequests) }
        }

    @Test
    fun `forward delete reports the unchanged caret with the edited text`() =
        runSkikoComposeUiTest(size = Size(1200f, 700f)) {
            var draft by mutableStateOf("/rex tail")
            val positions = mutableListOf<Pair<String, Int>>()
            setContent {
                SuggestionsComposer(
                    draft,
                    { draft = it },
                    {},
                    null,
                    onAccept = {},
                    onCaret = { positions += draft to it },
                )
            }
            val editor = onNodeWithContentDescription("Prompt")
            editor.performTextInputSelection(TextRange(2))
            runOnIdle { positions.clear() }
            editor.performKeyInput { pressKey(Key.Delete) }
            runOnIdle {
                assertEquals("/rx tail", draft)
                assertTrue(("/rx tail" to 2) in positions)
            }
        }

    @Test
    fun `keyboard navigation reveals the selected row beyond the popup viewport`() =
        runSkikoComposeUiTest(size = Size(1200f, 700f)) {
            var selected by mutableIntStateOf(0)
            val labels = (0 until 30).map { "File $it" }
            setContent {
                val items = labels.mapIndexed { index, label ->
                    HbComposerSuggestion(label, label, isSelected = index == selected)
                }.toImmutableList()
                SuggestionsComposer(
                    "/",
                    {},
                    {},
                    HbComposerSuggestions(items, selected),
                    onAccept = {},
                    onMove = { selected = (selected + it).mod(items.size) },
                )
            }
            val editor = onNodeWithContentDescription("Prompt")
            editor.performMouseInput { click() }
            onNodeWithText("File 0").assertIsSelected().assertIsDisplayed()
            // Up wraps to the last row, initially well below the scroll viewport.
            editor.performKeyInput { pressKey(Key.DirectionUp) }
            onNodeWithText("File 29").assertIsSelected().assertIsDisplayed()
            editor.assertIsFocused()
        }

    @Test
    fun `progressive insertion keeps the selected row visible without changing its identity`() =
        runSkikoComposeUiTest(size = Size(1280f, 700f)) {
            val selected = HbComposerSuggestion("selected", "Selected host match", isSelected = true)
            var suggestions by mutableStateOf(HbComposerSuggestions(persistentListOf(selected)))
            var accepted by mutableIntStateOf(-1)
            setContent { SuggestionsComposer("/", {}, {}, suggestions, onAccept = { accepted = it }) }
            val editor = onNodeWithContentDescription("Prompt")
            editor.performMouseInput { click() }
            onNodeWithText(selected.label).assertIsSelected().assertIsDisplayed()
            runOnIdle {
                val arrived = (0 until 15).map { HbComposerSuggestion("native-$it", "Native match $it") }
                suggestions = HbComposerSuggestions((arrived + selected).toImmutableList(), arrived.size)
            }
            onNodeWithText(selected.label).assertIsSelected().assertIsDisplayed()
            editor.assertIsFocused().performKeyInput { pressKey(Key.Tab) }
            runOnIdle { assertEquals(15, accepted) }
        }

    @Test
    fun `long titles and descriptions stay on one line across themes and widths`() {
        for (width in listOf(420f, 1280f)) {
            for (dark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width, 700f)) {
                    val title = "Проверить длинное название навыка и все изменения проекта ".repeat(8)
                    val description = "features/project/src/main/very-long-resource-name/".repeat(8)
                    val items = persistentListOf(
                        HbComposerSuggestion("long", title, description, isSelected = true),
                        HbComposerSuggestion("next", "Next suggestion", "Another useful description"),
                    )
                    setContent {
                        SuggestionsComposer("/", {}, {}, HbComposerSuggestions(items), {}, darkTheme = dark)
                    }
                    val editor = onNodeWithContentDescription("Prompt")
                    editor.performMouseInput { click() }
                    for (text in listOf(title, description)) {
                        val layouts = mutableListOf<TextLayoutResult>()
                        onNodeWithText(text, useUnmergedTree = true)
                            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                        assertEquals(1, layouts.single().lineCount)
                    }
                    onNodeWithText("Next suggestion").assertIsDisplayed()
                    editor.assertIsFocused()
                    val directory = File("build/composer-suggestions-ui").apply { mkdirs() }
                    check(
                        ImageIO.write(
                            captureToImage().toAwtImage(),
                            "png",
                            File(directory, "long-rows-${width.toInt()}-$dark.png"),
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `keyboard selection has a visible fill in both themes`() {
        for (dark in listOf(false, true)) {
            runSkikoComposeUiTest(size = Size(1200f, 700f)) {
                var selected by mutableIntStateOf(0)
                var selectedColor = 0
                setContent {
                    HbTheme(darkTheme = dark) {
                        selectedColor = HbTheme.colors.selectedContainer.toArgb()
                        SuggestionsComposer(
                            "/",
                            {},
                            {},
                            sampleSuggestions(selected),
                            onAccept = {},
                            onMove = { selected += it },
                            darkTheme = dark,
                        )
                    }
                }
                val editor = onNodeWithContentDescription("Prompt")
                editor.performMouseInput { click() }
                editor.performKeyInput { pressKey(Key.DirectionDown) }
                val row = onNodeWithText("Verify the change").assertIsSelected().fetchSemanticsNode().boundsInWindow
                val image = captureToImage().toAwtImage()
                assertEquals(selectedColor, image.getRGB((row.right - 4).toInt(), row.center.y.toInt()))
            }
        }
    }

    @Test
    fun `outside clicks reach Send and release the suggestion editor focus`() =
        runSkikoComposeUiTest(size = Size(1200f, 700f)) {
            var sends by mutableIntStateOf(0)
            setContent {
                SuggestionsComposer("@", {}, { sends++ }, sampleSuggestions(), onAccept = {})
            }
            onNodeWithContentDescription("Prompt").performMouseInput { click() }
            onNodeWithText("Remember a lesson").assertExists()
            onNodeWithContentDescription("Send").performMouseInput {
                moveTo(center)
                click()
            }
            runOnIdle { assertEquals(1, sends) }
            onNodeWithText("Remember a lesson").assertDoesNotExist()
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
            onNodeWithText("Read UTF-8 files").performMouseInput {
                moveTo(center)
                press()
            }
            onNodeWithContentDescription("Prompt").assertIsFocused()
            onNodeWithText("Read UTF-8 files").performMouseInput { release() }
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
    caretRequest: HbComposerCaretRequest? = null,
    onCaretRequestApply: (Long) -> Unit = {},
    darkTheme: Boolean = false,
) {
    HbTheme(darkTheme = darkTheme) {
        HbInWindowPopupHost {
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
                        suggestionsCloseLabel = "Close suggestions",
                        onSuggestionMove = onMove,
                        onSuggestionAccept = onAccept,
                        onSuggestionsDismiss = onDismiss,
                        onCaretChange = onCaret,
                        caretRequest = caretRequest,
                        onCaretRequestApply = onCaretRequestApply,
                    )
                }
            }
        }
    }
}

private fun sampleSuggestions(selectedIndex: Int = 0): HbComposerSuggestions = HbComposerSuggestions(
    items = persistentListOf(
        HbComposerSuggestion("remember", "Remember a lesson", "Save a durable instruction", "Commands"),
        HbComposerSuggestion("verify", "Verify the change", "Run applicable checks", null),
        HbComposerSuggestion("utf8", "Read UTF-8 files", "Encoding helper", "Skills"),
    ).mapIndexed { index, item -> item.copy(isSelected = index == selectedIndex) }.toImmutableList(),
    selectedIndex = selectedIndex,
)
