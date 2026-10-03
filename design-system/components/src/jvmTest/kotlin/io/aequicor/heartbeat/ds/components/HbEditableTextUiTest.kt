package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbEditableTextUiTest {
    @Test
    fun `rejected proposal restores controlled text without reporting the restoration`() = runSkikoComposeUiTest {
        val proposals = mutableListOf<String>()
        setContent { EditorTestHost("Keep", { proposals += it }) }
        val editor = onNodeWithTag("editor")
        editor.performTextInput(" rejected")
        editor.assertEditorText("Keep")
        editor.assertIsFocused()
        runOnIdle { assertEquals(listOf("Keep rejected"), proposals) }
        editor.performTextInputSelection(TextRange(1))
        editor.performTextInput("!")
        editor.assertEditorText("Keep")
        runOnIdle { assertEquals(listOf("Keep rejected", "K!eep"), proposals) }
    }

    @Test
    fun `rejected character preserves the caret for the next accepted character`() = runSkikoComposeUiTest {
        var value by mutableStateOf("1234")
        val proposals = mutableListOf<String>()
        setContent {
            EditorTestHost(value, {
                proposals += it
                value = it.filter(Char::isDigit)
            })
        }
        val editor = onNodeWithTag("editor")
        editor.performTextInputSelection(TextRange(1))
        editor.performTextInput("a")
        editor.assertEditorText("1234")
        editor.assertEditorSelection(TextRange(1))
        editor.performTextInput("5")
        editor.assertEditorText("15234")
        editor.assertEditorSelection(TextRange(2))
        runOnIdle { assertEquals(listOf("1a234", "15234"), proposals) }
    }

    @Test
    fun `rejected replacement preserves reversed selection for the next accepted edit`() = runSkikoComposeUiTest {
        var value by mutableStateOf("1234")
        val proposals = mutableListOf<String>()
        setContent {
            EditorTestHost(value, {
                proposals += it
                if (it.all(Char::isDigit)) value = it
            })
        }
        val editor = onNodeWithTag("editor")
        editor.performSemanticsAction(SemanticsActions.RequestFocus)
        // performTextInputSelection normalizes endpoints; exercise the actual reversed-selection action.
        editor.performSemanticsAction(SemanticsActions.SetSelection) { it(3, 1, true) }
        editor.assertEditorSelection(TextRange(3, 1))
        editor.performTextInput("a")
        editor.assertEditorText("1234")
        editor.assertEditorSelection(TextRange(3, 1))
        editor.performTextInput("5")
        editor.assertEditorText("154")
        editor.assertEditorSelection(TextRange(2))
        runOnIdle { assertEquals(listOf("1a4", "154"), proposals) }
    }

    @Test
    fun `caller filter applies without publishing the programmatic replacement`() = runSkikoComposeUiTest {
        var value by mutableStateOf("12")
        val proposals = mutableListOf<String>()
        setContent {
            EditorTestHost(value, {
                proposals += it
                value = it.filter(Char::isDigit)
            })
        }
        val editor = onNodeWithTag("editor")
        editor.performTextInput("a3b")
        editor.assertEditorText("123")
        editor.performTextInput("4")
        editor.assertEditorText("1234")
        runOnIdle { assertEquals(listOf("12a3b", "1234"), proposals) }
    }

    @Test
    fun `external clear retains focus clamps selection and emits no input callback`() = runSkikoComposeUiTest {
        var value by mutableStateOf("Clear this draft")
        val proposals = mutableListOf<String>()
        setContent {
            EditorTestHost(value, {
                proposals += it
                value = it
            })
        }
        val editor = onNodeWithTag("editor")
        editor.performTextInputSelection(TextRange(3, 10))
        runOnIdle { value = "" }
        editor.assertEditorText("")
        editor.assertEditorSelection(TextRange.Zero)
        editor.assertIsFocused()
        runOnIdle { assertTrue(proposals.isEmpty()) }
        editor.performTextInput("Next")
        editor.assertEditorText("Next")
        runOnIdle { assertEquals(listOf("Next"), proposals) }
    }

    @Test
    fun `owner text keeps a caret at the end and follows a prefix`() = runSkikoComposeUiTest {
        var value by mutableStateOf("")
        setContent { EditorTestHost(value, { value = it }) }
        val editor = onNodeWithTag("editor")
        editor.performTextInputSelection(TextRange.Zero)
        runOnIdle { value = "/remember " }
        editor.assertEditorSelection(TextRange(10))
        editor.performTextInput("Use LF")
        editor.assertEditorText("/remember Use LF")

        runOnIdle { value = "use LF" }
        editor.performTextInputSelection(TextRange(3))
        runOnIdle { value = "/remember use LF" }
        editor.assertEditorSelection(TextRange(13))
    }

    @Test
    fun `accepted edits preserve caret and selection across unrelated recomposition`() = runSkikoComposeUiTest {
        var value by mutableStateOf("abcd")
        var isDark by mutableStateOf(false)
        val proposals = mutableListOf<String>()
        setContent {
            EditorTestHost(value, {
                proposals += it
                value = it
            }, isDark = isDark)
        }
        val editor = onNodeWithTag("editor")
        editor.performTextInputSelection(TextRange(2))
        editor.performTextInput("X")
        editor.assertEditorText("abXcd")
        editor.assertEditorSelection(TextRange(3))
        editor.performTextInputSelection(TextRange(1, 4))
        runOnIdle { isDark = true }
        editor.assertEditorSelection(TextRange(1, 4))
        editor.performTextInput("Y")
        editor.assertEditorText("aYd")
        editor.assertEditorSelection(TextRange(2))
        editor.assertIsFocused()
        runOnIdle { assertEquals(listOf("abXcd", "aYd"), proposals) }
    }

    @Test
    fun `two edits in one frame report a return to the original controlled value`() = runSkikoComposeUiTest {
        var value by mutableStateOf("seed")
        val proposals = mutableListOf<String>()
        setContent {
            EditorTestHost(value, {
                proposals += it
                value = it
            })
        }
        val editor = onNodeWithTag("editor")
        editor.performSemanticsAction(SemanticsActions.RequestFocus)
        val semantics = editor.fetchSemanticsNode().config
        val insert = checkNotNull(semantics[SemanticsActions.InsertTextAtCursor].action)
        val replace = checkNotNull(semantics[SemanticsActions.SetText].action)
        runOnIdle {
            insert(AnnotatedString("x"))
            replace(AnnotatedString("seed"))
        }
        editor.assertEditorText("seed")
        runOnIdle {
            assertEquals("seed", value)
            assertEquals(listOf("seedx", "seed"), proposals)
        }
    }

    @Test
    fun `keyboard undo and redo update the controlled value once per edit`() = runSkikoComposeUiTest {
        var value by mutableStateOf("")
        val proposals = mutableListOf<String>()
        setContent {
            EditorTestHost(value, {
                proposals += it
                value = it
            })
        }
        val editor = onNodeWithTag("editor")
        editor.performTextInput("draft")
        editor.performUndo()
        editor.assertEditorText("")
        runOnIdle { assertEquals("", value) }
        editor.performRedo()
        editor.assertEditorText("draft")
        editor.assertEditorSelection(TextRange(5))
        runOnIdle { assertEquals(listOf("draft", "", "draft"), proposals) }
    }

    @Test
    fun `rejected undo restores text and prior selection without a callback loop`() = runSkikoComposeUiTest {
        var value by mutableStateOf("")
        val proposals = mutableListOf<String>()
        setContent {
            EditorTestHost(value, {
                proposals += it
                if (it.isNotEmpty()) value = it
            })
        }
        val editor = onNodeWithTag("editor")
        editor.performTextInput("draft")
        editor.performSemanticsAction(SemanticsActions.SetSelection) { it(4, 1, true) }
        editor.assertEditorSelection(TextRange(4, 1))
        editor.performUndo()
        editor.assertEditorText("draft")
        editor.assertEditorSelection(TextRange(4, 1))
        editor.assertIsFocused()
        runOnIdle { assertEquals(listOf("draft", ""), proposals) }
        editor.performTextInput("X")
        editor.assertEditorText("dXt")
        runOnIdle { assertEquals(listOf("draft", "", "dXt"), proposals) }
    }

    @Test
    fun `multiline editor reveals its bar on edge hover and wheel movement without editing text`() =
        runSkikoComposeUiTest(size = Size(420f, 240f)) {
            val value = (0..39).joinToString("\n") { "Line $it" }
            var callbacks = 0
            setContent { ScrollableEditorTestHost(value, { callbacks++ }, singleLine = false) }
            val editor = onNodeWithTag("editor")
            val bounds = editor.fetchSemanticsNode().boundsInRoot
            val idle = captureToImage().toAwtImage()
            mainClock.autoAdvance = false
            editor.performMouseInput { moveTo(Offset(width - 3f, centerY)) }
            mainClock.advanceTimeBy(300)
            val hovered = captureToImage().toAwtImage()
            assertFalse(editorEdgePixels(idle, bounds).contentEquals(editorEdgePixels(hovered, bounds)))
            saveEditorPreview("multiline-hover", hovered)
            editor.performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(2000)
            val hidden = captureToImage().toAwtImage()
            assertTrue(editorEdgePixels(idle, bounds).contentEquals(editorEdgePixels(hidden, bounds)))
            editor.performMouseInput { scroll(3f) }
            mainClock.advanceTimeBy(300)
            val scrolled = captureToImage().toAwtImage()
            assertFalse(editorEdgePixels(hidden, bounds).contentEquals(editorEdgePixels(scrolled, bounds)))
            assertFalse(editorContentPixels(hidden, bounds).contentEquals(editorContentPixels(scrolled, bounds)))
            editor.assertEditorText(value)
            runOnIdle { assertEquals(0, callbacks) }
            saveEditorPreview("multiline-scroll", scrolled)
        }

    @Test
    fun `single line overflow scrolls horizontally and reveals its bottom bar`() =
        runSkikoComposeUiTest(size = Size(420f, 240f)) {
            val value = (0..79).joinToString(" · ") { "Column $it" }
            var callbacks = 0
            setContent { ScrollableEditorTestHost(value, { callbacks++ }, singleLine = true) }
            val editor = onNodeWithTag("editor")
            val bounds = editor.fetchSemanticsNode().boundsInRoot
            val idle = captureToImage().toAwtImage()
            mainClock.autoAdvance = false
            editor.performMouseInput { moveTo(Offset(centerX, height - 3f)) }
            mainClock.advanceTimeBy(300)
            val hovered = captureToImage().toAwtImage()
            assertFalse(
                editorEdgePixels(idle, bounds, isHorizontal = true)
                    .contentEquals(editorEdgePixels(hovered, bounds, isHorizontal = true)),
            )
            editor.performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(2000)
            val hidden = captureToImage().toAwtImage()
            editor.performMouseInput { scroll(3f, ScrollWheel.Horizontal) }
            mainClock.advanceTimeBy(300)
            val scrolled = captureToImage().toAwtImage()
            assertFalse(
                editorEdgePixels(hidden, bounds, isHorizontal = true)
                    .contentEquals(editorEdgePixels(scrolled, bounds, isHorizontal = true)),
            )
            assertFalse(editorContentPixels(hidden, bounds).contentEquals(editorContentPixels(scrolled, bounds)))
            editor.assertEditorText(value)
            runOnIdle { assertEquals(0, callbacks) }
            saveEditorPreview("single-line-scroll", scrolled)
        }
}

@Composable
private fun EditorTestHost(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = false,
) {
    HbTheme(darkTheme = isDark) {
        HbTextField(value, onValueChange, modifier = modifier.size(280.dp, 96.dp).testTag("editor"))
    }
}

@Composable
private fun ScrollableEditorTestHost(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
) {
    HbTheme(darkTheme = false) {
        Box(modifier.fillMaxSize().background(HbTheme.colors.background), contentAlignment = Alignment.Center) {
            HbEditableText(
                value = value,
                onValueChange = onValueChange,
                interactionSource = remember { MutableInteractionSource() },
                modifier = Modifier.size(280.dp, 96.dp).testTag("editor"),
                singleLine = singleLine,
            )
        }
    }
}

private fun SemanticsNodeInteraction.assertEditorText(expected: String) {
    assertEquals(expected, fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
}

private fun SemanticsNodeInteraction.assertEditorSelection(expected: TextRange) {
    assertEquals(expected, fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
}

private fun editorEdgePixels(image: BufferedImage, bounds: Rect, isHorizontal: Boolean = false): IntArray {
    val left = if (isHorizontal) bounds.left.toInt() else bounds.right.toInt() - 12
    val top = if (isHorizontal) bounds.bottom.toInt() - 10 else bounds.top.toInt()
    val width = if (isHorizontal) bounds.width.toInt() else 12
    val height = if (isHorizontal) 10 else bounds.height.toInt()
    return image.getRGB(left, top, width, height, null, 0, width)
}

private fun editorContentPixels(image: BufferedImage, bounds: Rect): IntArray {
    val width = bounds.width.toInt() - 16
    val height = bounds.height.toInt() - 12
    return image.getRGB(bounds.left.toInt(), bounds.top.toInt(), width, height, null, 0, width)
}

private fun saveEditorPreview(name: String, image: BufferedImage) {
    val directory = File("build/previews")
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create preview directory" }
    check(ImageIO.write(image, "png", File(directory, "editor-$name.png"))) { "PNG encoder is unavailable" }
}

// Compose uses the host platform's editor shortcuts, including Command+Shift+Z on macOS.
private fun SemanticsNodeInteraction.performUndo() = performKeyInput {
    withKeyDown(if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft) {
        pressKey(Key.Z)
    }
}

private fun SemanticsNodeInteraction.performRedo() = performKeyInput {
    if (System.getProperty("os.name").startsWith("Mac")) {
        withKeyDown(Key.MetaLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Z) } }
    } else {
        withKeyDown(Key.CtrlLeft) { pressKey(Key.Y) }
    }
}
