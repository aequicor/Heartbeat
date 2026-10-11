package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbCodeEditorUiTest {
    @Test
    fun `Tab indents and Shift+Tab outdents through the live text and both reach the owner`() = runSkikoComposeUiTest {
        var value by mutableStateOf(SOURCE)
        val proposals = mutableListOf<String>()
        setContent {
            CodeEditorHost(value, {
                proposals += it
                value = it
            })
        }
        val editor = codeEditor()
        editor.performTextInputSelection(TextRange(0))
        editor.performKeyInput { pressKey(Key.Tab) }
        editor.assertCodeText("    $SOURCE")
        editor.assertCodeSelection(TextRange(4))

        editor.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        editor.assertCodeText(SOURCE)
        editor.assertCodeSelection(TextRange(0))
        runOnIdle { assertEquals(listOf("    $SOURCE", SOURCE), proposals) }
    }

    @Test
    fun `Ctrl+Tab moves focus forward and backward instead of indenting`() = runSkikoComposeUiTest {
        val proposals = mutableListOf<String>()
        setContent {
            HbTheme(darkTheme = false) {
                Column {
                    HbTextField("", {}, Modifier.testTag("previous"))
                    HbCodeEditor(SOURCE, { proposals += it }, LABEL, Modifier.size(320.dp, 120.dp).testTag("code"))
                    HbTextField("", {}, Modifier.testTag("next"))
                }
            }
        }
        val editor = codeEditor()
        editor.performTextInputSelection(TextRange(0))
        editor.assertIsFocused()
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Tab) } }
        onNodeWithTag("next").assertIsFocused()

        editor.performTextInputSelection(TextRange(0))
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } } }
        onNodeWithTag("previous").assertIsFocused()
        editor.assertCodeText(SOURCE)
        runOnIdle { assertTrue(proposals.isEmpty()) }
    }

    @Test
    fun `Tab in a read-only editor leaves the text unchanged`() = runSkikoComposeUiTest {
        val proposals = mutableListOf<String>()
        setContent { CodeEditorHost(SOURCE, { proposals += it }, isReadOnly = true) }
        val editor = codeEditor()
        editor.performSemanticsAction(SemanticsActions.RequestFocus)
        editor.assertIsFocused()
        editor.performKeyInput { pressKey(Key.Tab) }
        editor.assertCodeText(SOURCE)
        runOnIdle { assertTrue(proposals.isEmpty()) }
    }

    @Test
    fun `error lines get the error fill in the gutter`() = runSkikoComposeUiTest {
        var errorLines by mutableStateOf<ImmutableSet<Int>>(persistentSetOf())
        var error = Color.Unspecified
        setContent {
            HbTheme(darkTheme = false) {
                error = HbTheme.colors.error
                HbCodeEditor(
                    "val a = 1\nval b = \nval c = 3",
                    {},
                    LABEL,
                    Modifier.size(320.dp, 120.dp).testTag("code"),
                    errorLines = errorLines,
                )
            }
        }
        val bounds = onNodeWithTag("code").fetchSemanticsNode().boundsInRoot
        assertEquals(0, gutterPixels(captureToImage().toAwtImage(), bounds, error))
        runOnIdle { errorLines = persistentSetOf(2) }
        val marked = gutterPixels(captureToImage().toAwtImage(), bounds, error)
        assertTrue(marked > 0, "the marked line is filled with the error colour")
    }

    @Test
    fun `an editor in error announces its message instead of the label`() = runSkikoComposeUiTest {
        var isError by mutableStateOf(false)
        setContent { CodeEditorHost(SOURCE, {}, isError = isError) }
        codeEditor().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        runOnIdle { isError = true }
        codeEditor().assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, ERROR))
    }

    @Test
    fun `the keyboard hint follows the label for accessibility services`() = runSkikoComposeUiTest {
        setContent { CodeEditorHost(SOURCE, {}) }
        codeEditor().assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(LABEL, HINT)))
    }
}

@Composable
private fun CodeEditorHost(
    value: String,
    onValueChange: (String) -> Unit,
    isReadOnly: Boolean = false,
    isError: Boolean = false,
) {
    HbTheme(darkTheme = false) {
        HbCodeEditor(
            value,
            onValueChange,
            LABEL,
            Modifier.size(320.dp, 120.dp).testTag("code"),
            language = "kotlin",
            isReadOnly = isReadOnly,
            isError = isError,
            errorMessage = ERROR,
            keyboardHint = HINT,
        )
    }
}

private fun SemanticsNodeInteractionsProvider.codeEditor(): SemanticsNodeInteraction =
    onNode(hasContentDescription(LABEL))

/** Pixels of [color] in the left quarter of the editor [bounds] (the gutter), away from the field edges. */
private fun gutterPixels(image: BufferedImage, bounds: Rect, color: Color): Int {
    val target = color.toArgb()
    var count = 0
    for (x in bounds.left.toInt() + EDGE until (bounds.left + bounds.width / 4).toInt()) {
        for (y in bounds.top.toInt() + EDGE until bounds.bottom.toInt() - EDGE) {
            if (image.getRGB(x, y).isNear(target)) count++
        }
    }
    return count
}

private fun Int.isNear(other: Int): Boolean = listOf(0, 8, 16).all { shift ->
    abs((this shr shift and 0xFF) - (other shr shift and 0xFF)) <= 2
}

private fun SemanticsNodeInteraction.assertCodeText(expected: String) {
    assertEquals(expected, fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
}

private fun SemanticsNodeInteraction.assertCodeSelection(expected: TextRange) {
    assertEquals(expected, fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
}

private const val LABEL = "Script"
private const val ERROR = "Line 2 does not compile"
private const val HINT = "Ctrl+Tab moves to the next field"
private const val SOURCE = "val a = 1\nval b = 2"
private const val EDGE = 4
