package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
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
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.persistentListOf
import java.awt.Canvas
import kotlin.test.Test
import kotlin.test.assertEquals
import java.awt.event.KeyEvent as AwtKeyEvent

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class HbComposerKeyInputUiTest {
    @Test
    fun `mouse focused panel receives ordinary key events newline and send shortcut`() =
        runSkikoComposeUiTest(size = Size(1200f, 700f)) {
            var draft by mutableStateOf("")
            var sends = 0
            setContent { KeyInputComposer(draft, { draft = it }, { sends++ }) }
            val editor = onNodeWithContentDescription("Prompt")
            editor.performMouseInput { click() }
            editor.assertIsFocused()
            typeAwtText("abc Привет")
            editor.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
            typeAwtText("d")
            runOnIdle { assertEquals("abc Привет\nd", draft) }
            editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
            runOnIdle {
                assertEquals(1, sends)
                assertEquals("abc Привет\nd", draft)
            }
        }

    @Test
    fun `editor receives physical keys after project and composer popup dismissal`() =
        runSkikoComposeUiTest(size = Size(1200f, 700f)) {
            var draft by mutableStateOf("")
            setContent { KeyInputComposer(draft, { draft = it }, {}) }
            onNodeWithContentDescription("Project").performMouseInput { click() }
            onNodeWithText("No project").performKeyInput { pressKey(Key.Escape) }
            onNodeWithText("No project").assertDoesNotExist()
            val editor = onNodeWithContentDescription("Prompt")
            editor.performMouseInput { click() }
            editor.assertIsFocused()
            typeAwtText("a")
            runOnIdle { assertEquals("a", draft) }
            onNodeWithContentDescription("Add").performMouseInput { click() }
            onNodeWithText("Plan").performKeyInput { pressKey(Key.Escape) }
            onNodeWithText("Plan").assertDoesNotExist()
            editor.performMouseInput { click() }
            editor.assertIsFocused()
            typeAwtText("b")
            runOnIdle { assertEquals("ab", draft) }
        }
}

/** Desktop text handling requires AWT KEY_TYPED; pressKey injects only non-text down/up events. */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
private fun SkikoComposeUiTest.typeAwtText(text: String) {
    runOnUiThread {
        val source = Canvas()
        text.forEach { character ->
            val native = AwtKeyEvent(source, AwtKeyEvent.KEY_TYPED, 0, 0, AwtKeyEvent.VK_UNDEFINED, character)
            scene.sendKeyEvent(
                KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = character.code, nativeEvent = native),
            )
        }
    }
    waitForIdle()
}

@Composable
private fun KeyInputComposer(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit) {
    var isProjectOpen by remember { mutableStateOf(false) }
    var isAddOpen by remember { mutableStateOf(false) }
    HbTheme {
        Box(Modifier.fillMaxSize().background(HbTheme.colors.background)) {
            HbColumn(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                Box {
                    HbChip("Project", onClick = { isProjectOpen = !isProjectOpen })
                    HbMenu(
                        persistentListOf(HbMenuItem("none", "No project")),
                        isProjectOpen,
                        { isProjectOpen = false },
                        {},
                        "Project menu",
                    )
                }
                HbChatComposer(
                    value, onValueChange, onSend, {}, "Send", "Stop",
                    layout = HbComposerLayout.Panel,
                    placeholder = "Prompt",
                    leadingContent = {
                        HbComposerMenuButton(
                            "Add",
                            persistentListOf(HbComposerAction("plan", "Plan")),
                            isAddOpen,
                            { isAddOpen = it },
                            {},
                            icon = HbIcons.Plus,
                            style = HbComposerMenuStyle.Circle,
                        )
                    },
                )
            }
        }
    }
}
