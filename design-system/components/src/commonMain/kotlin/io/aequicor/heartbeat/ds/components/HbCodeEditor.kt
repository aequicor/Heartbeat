package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbScrollbars
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbColors
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf

private val log = Log.tag("HbCodeEditor")

/**
 * Plain multi-line editor for code and markdown sources: monospace text without soft wrap, a line-number gutter
 * sharing the vertical scroll, scrollbars on both axes and a flat field with the kit's text-input focus outline.
 * A Foundation component for every kit: platform text areas wrap lines and cannot share their scroll with a gutter.
 *
 * [language] is a fence-style language name (`kotlin`, `json`…); null or an unknown name shows plain text.
 * [errorLines] (1-based) are marked in the gutter with the error fill and a `!`; the gutter is hidden from
 * accessibility, so callers list diagnostics as text next to the editor. [isError] also tints the field outline.
 * Tab indents the caret or the selected lines, Shift+Tab outdents them, and Ctrl/⌘+Tab (with Shift — backwards)
 * moves focus, because Tab is part of the text. Touch keyboards have no Tab key, so indentation is typed there.
 * A read-only editor stays focusable, selectable and scrollable. The caller owns the text; logs never contain it.
 */
@Composable
public fun HbCodeEditor(
    value: String,
    onValueChange: (String) -> Unit,
    accessibleLabel: String,
    modifier: Modifier = Modifier,
    language: String? = null,
    isReadOnly: Boolean = false,
    isError: Boolean = false,
    errorLines: ImmutableSet<Int> = persistentSetOf(),
    placeholder: String = "",
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val colors = HbTheme.colors
    val shape = RoundedCornerShape(HbTheme.dimensions.fieldCornerRadius)
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val focus = LocalFocusManager.current
    val highlight = remember(language, colors) { syntaxTransformation(language, colors) }
    val padding = HbTheme.spacing.s
    HbRow(
        modifier
            .hbFocusOutline(isFocused, shape, isTextInput = true)
            .then(if (isError) Modifier.border(HbTheme.dimensions.borderWidth, colors.error, shape) else Modifier)
            .background(colors.inputFill, shape)
            .clipToBounds(),
        gap = HbTheme.spacing.none,
    ) {
        LineGutter(value, errorLines, vertical, PaddingValues(vertical = padding), Modifier.fillMaxHeight())
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxHeight().hbScrollbars(vertical)
                .hbScrollbars(horizontal, orientation = Orientation.Horizontal),
        ) {
            val viewport = maxWidth
            HbEditableText(
                value = value,
                onValueChange = onValueChange,
                interactionSource = interactionSource,
                modifier = Modifier.horizontalScroll(horizontal).widthIn(min = viewport).fillMaxHeight()
                    .semantics { contentDescription = accessibleLabel },
                placeholder = placeholder,
                contentPadding = PaddingValues(horizontal = padding, vertical = padding),
                textStyle = HbTheme.typography.code.copy(color = colors.textPrimary),
                isReadOnly = isReadOnly,
                outputTransformation = highlight,
                scrollState = vertical,
                onPreviewKey = { event, state ->
                    codeKey(event, state, isReadOnly) { direction -> focus.moveFocus(direction) }
                },
            )
        }
    }
}

/**
 * Line numbers aligned with the text: one text node in the same code style, rebuilt only when the text or the
 * marked lines change; the scroll offset is read during placement, so scrolling does not recompose the editor.
 */
@Composable
private fun LineGutter(
    text: String,
    errorLines: ImmutableSet<Int>,
    scroll: ScrollState,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val colors = HbTheme.colors
    val style = HbTheme.typography.code
    val numbers = remember(text, errorLines, colors) { gutterText(hbCodeLineCount(text), errorLines, colors) }
    Box(modifier.clipToBounds().clearAndSetSemantics {}) {
        BasicText(
            numbers,
            Modifier.offset { IntOffset(0, -scroll.value) }.padding(padding).padding(horizontal = HbTheme.spacing.s),
            style = style.copy(color = colors.textSecondary),
            softWrap = false,
        )
    }
}

/** Right-aligned numbers with a trailing marker column: [errorLines] get `!` on the error fill. */
private fun gutterText(count: Int, errorLines: Set<Int>, colors: HbColors): AnnotatedString {
    val digits = count.toString().length
    return buildAnnotatedString {
        for (line in 1..count) {
            if (line > 1) append('\n')
            val number = line.toString().padStart(digits)
            if (line in errorLines) {
                withStyle(SpanStyle(color = colors.onError, background = colors.error)) { append("$number !") }
            } else {
                append("$number  ")
            }
        }
    }
}

/** Highlights recognised languages; spans never change the text, so selection and caret stay exact. */
private fun syntaxTransformation(language: String?, colors: HbColors): OutputTransformation? {
    if (language == null || hbCodeLanguage(language) == null) return null
    return OutputTransformation {
        highlightHbCode(asCharSequence().toString(), language).forEach { span ->
            addStyle(SpanStyle(color = colors.syntaxColor(span.kind)), span.start, span.end)
        }
    }
}

/**
 * Applies editor keys to the live state; edits are reported to the owner like typing. Only the changed range is
 * replaced, so undo steps over one indentation command and nothing else.
 */
private fun codeKey(
    event: KeyEvent,
    state: TextFieldState,
    isReadOnly: Boolean,
    moveFocus: (FocusDirection) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown || event.key != Key.Tab) return false
    if (event.isCtrlPressed || event.isMetaPressed) {
        moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
        return true
    }
    if (isReadOnly) return false
    val text = state.text.toString()
    val edit = if (event.isShiftPressed) {
        outdentHbCode(text, state.selection)
    } else {
        indentHbCode(text, state.selection)
    }
    if (edit != null) {
        log.v { "code indentation changed" }
        val change = hbCodeChange(text, edit.text)
        state.edit {
            replace(change.start, change.oldEnd, change.replacement)
            selection = edit.selection
        }
    }
    return true
}

private val previewSource = """
    hooks.beforeTool { call ->
        if (call.name == "run_command") ToolHookVerdict.Ask("Review the command") else ToolHookVerdict.Continue
    }
""".trimIndent()

@Preview
@Composable
private fun HbCodeEditorLightPreview() {
    HbTheme(darkTheme = false) {
        HbCodeEditor(
            previewSource,
            {},
            "Script",
            Modifier.fillMaxWidth(),
            language = "kotlin",
            isError = true,
            errorLines = persistentSetOf(2),
        )
    }
}

@Preview
@Composable
private fun HbCodeEditorDarkPreview() {
    HbTheme(darkTheme = true) {
        HbCodeEditor(previewSource, {}, "Script", Modifier.fillMaxWidth(), language = "kotlin", isReadOnly = true)
    }
}

@Preview
@Composable
private fun HbCodeEditorLongTextPreview() {
    HbTheme(darkTheme = true) {
        HbCodeEditor(
            (1..40).joinToString("\n") { "val line$it = \"${"x".repeat(it * 3)}\"" },
            {},
            "Script",
            Modifier.fillMaxWidth().height(HbTheme.dimensions.codeEditorCompactHeight),
            language = "kotlin",
            isError = true,
            errorLines = persistentSetOf(3, 17),
        )
    }
}
