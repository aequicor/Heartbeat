package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextRange
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HbComposerReferenceClipboardUiTest {
    private val link = "[/open-pr](.agents/skills/open-pr/SKILL.md)"

    @Test
    fun `copying a whole skill keeps its hidden target`() = runSkikoComposeUiTest {
        val clipboard = RecordingClipboard()
        var draft by mutableStateOf("run $link now")
        setContent { Composer(draft, { draft = it }, clipboard) }
        val editor = onNodeWithContentDescription("Prompt")
        editor.performTextInputSelection(TextRange(0, draft.length))
        editor.performSemanticsAction(SemanticsActions.CopyText)
        runOnIdle {
            assertEquals("run $link now", clipboard.text)
            assertEquals("run $link now", draft)
        }
    }

    @Test
    fun `cutting a whole skill keeps its target and pasting restores the link`() = runSkikoComposeUiTest {
        val clipboard = RecordingClipboard()
        var draft by mutableStateOf("$link tail")
        setContent { Composer(draft, { draft = it }, clipboard) }
        val editor = onNodeWithContentDescription("Prompt")
        editor.performTextInputSelection(TextRange(0, link.length))
        editor.performSemanticsAction(SemanticsActions.CutText)
        runOnIdle {
            assertEquals(link, clipboard.text)
            assertEquals(" tail", draft)
        }
        editor.performTextInputSelection(TextRange(draft.length))
        editor.performSemanticsAction(SemanticsActions.PasteText)
        // Paste reads the clipboard in a coroutine; wait for its edit instead of one idle frame.
        waitUntil(timeoutMillis = PASTE_TIMEOUT_MS) { draft == " tail$link" }
    }

    @Test
    fun `copying part of a skill label copies only the visible text`() = runSkikoComposeUiTest {
        val clipboard = RecordingClipboard()
        var draft by mutableStateOf("$link tail")
        setContent { Composer(draft, { draft = it }, clipboard) }
        val editor = onNodeWithContentDescription("Prompt")
        editor.performTextInputSelection(TextRange(2, 6))
        editor.performSemanticsAction(SemanticsActions.CopyText)
        runOnIdle { assertEquals("open", clipboard.text) }
    }

    @androidx.compose.runtime.Composable
    private fun Composer(draft: String, onDraft: (String) -> Unit, clipboard: Clipboard) {
        HbTheme {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                HbChatComposer(
                    value = draft,
                    onValueChange = onDraft,
                    onSend = {},
                    onStop = {},
                    sendLabel = "Send",
                    stopLabel = "Stop",
                    layout = HbComposerLayout.Panel,
                    placeholder = "Prompt",
                )
            }
        }
    }
}

private class RecordingClipboard : Clipboard {
    private var entry: ClipEntry? = null

    val text: String? get() = entry?.let(::hbClipEntryPlainText)

    override suspend fun getClipEntry(): ClipEntry? = entry

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        entry = clipEntry
    }
}

private const val PASTE_TIMEOUT_MS = 5_000L
