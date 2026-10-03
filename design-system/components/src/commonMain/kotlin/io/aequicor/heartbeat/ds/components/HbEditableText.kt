package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.inputText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.hbScrollbars
import io.aequicor.heartbeat.ds.theme.HbTheme

private val log = Log.tag("DS/EditableText")

/** Frames an owner may take to publish a reported edit before an unchanged value counts as a rejection. */
private const val REJECTION_GRACE_FRAMES = 2

/**
 * Controlled text with a hoisted editor scroll state, shared by fields and the composer.
 * Owners may answer asynchronously (for example a store on the main dispatcher): an edit is only
 * treated as rejected when [value] is still unchanged a few frames later, and late answers keep the caret.
 * Selection uses the current scoped palette without replacing selection of surrounding read-only text.
 *
 * [isSecret] switches to a secure single-line editor: the text is obfuscated, cut and copy are disabled,
 * the keyboard is a password keyboard without autocorrect, and the text is never written to saved state.
 * Accessibility receives the same hidden text as the screen, even on bridges that expose password values.
 */
@Composable
internal fun HbEditableText(
    value: String,
    onValueChange: (String) -> Unit,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(),
    isSecret: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val state = rememberEditorState(value, isSecret)
    val bridge = remember(state) { ControlledEditorBridge(value, state.selection) }
    val currentValue by rememberUpdatedState(value)
    // Reading text also observes rejected proposals and edits that bypass InputTransformation, such as undo.
    val editingText = state.text.toString()
    SideEffect(value, editingText, state.selection, bridge.revision) {
        bridge.reconcile(state, value, onValueChange)
    }
    LaunchedEffect(bridge, bridge.rejectionCheck) {
        if (bridge.rejectionCheck == 0) return@LaunchedEffect
        repeat(REJECTION_GRACE_FRAMES) { withFrameNanos { } }
        bridge.restoreIfUnanswered(state, currentValue)
    }
    val inputTransformation = InputTransformation { bridge.observeInput(this, onValueChange) }
    val decorator = placeholderDecorator(
        placeholder,
        editingText.isEmpty(),
        singleLine || isSecret,
        contentPadding,
        EditorAdornments(leadingContent, trailingContent),
    )
    val colors = HbTheme.colors
    val selectionColors = remember(colors) {
        TextSelectionColors(handleColor = colors.focusAccent, backgroundColor = colors.selectionHighlight)
    }
    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        if (isSecret) {
            val hiddenText = AnnotatedString("•".repeat(editingText.length))
            BasicSecureTextField(
                state = state,
                modifier = modifier.semantics {
                    editableText = hiddenText
                    inputText = hiddenText
                },
                enabled = enabled,
                inputTransformation = inputTransformation,
                textStyle = editorTextStyle(),
                keyboardOptions = SecretKeyboard,
                interactionSource = interactionSource,
                cursorBrush = editorCursor(),
                decorator = decorator,
                textObfuscationMode = TextObfuscationMode.Hidden,
            )
        } else {
            PlainEditor(
                state,
                enabled,
                singleLine,
                inputTransformation,
                interactionSource,
                decorator,
                modifier,
            )
        }
    }
}

/** A secret must not reach saved instance state; the owner re-supplies the controlled value anyway. */
@Composable
private fun rememberEditorState(value: String, isSecret: Boolean): TextFieldState =
    if (isSecret) remember { TextFieldState(value) } else rememberTextFieldState(value)

private data class EditorAdornments(
    val leadingContent: (@Composable () -> Unit)?,
    val trailingContent: (@Composable () -> Unit)?,
)

private fun placeholderDecorator(
    placeholder: String,
    isEmpty: Boolean,
    isSingleLine: Boolean,
    contentPadding: PaddingValues,
    adornments: EditorAdornments,
) = TextFieldDecorator { innerTextField ->
    if (adornments.leadingContent == null && adornments.trailingContent == null) {
        PlaceholderContent(placeholder, isEmpty, isSingleLine, innerTextField, Modifier.padding(contentPadding))
    } else {
        Row(
            modifier = Modifier.padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(HbTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            adornments.leadingContent?.invoke()
            PlaceholderContent(placeholder, isEmpty, isSingleLine, innerTextField, Modifier.weight(1f))
            adornments.trailingContent?.invoke()
        }
    }
}

@Composable
private fun PlaceholderContent(
    placeholder: String,
    isEmpty: Boolean,
    isSingleLine: Boolean,
    innerTextField: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = if (isSingleLine) Alignment.CenterStart else Alignment.TopStart) {
        if (isEmpty) HbText(placeholder, color = HbTheme.colors.textSecondary)
        innerTextField()
    }
}

@Composable
private fun PlainEditor(
    state: TextFieldState,
    enabled: Boolean,
    singleLine: Boolean,
    inputTransformation: InputTransformation,
    interactionSource: MutableInteractionSource,
    decorator: TextFieldDecorator,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    BasicTextField(
        state = state,
        modifier = modifier.hbScrollbars(
            scrollState,
            orientation = if (singleLine) Orientation.Horizontal else Orientation.Vertical,
        ),
        enabled = enabled,
        inputTransformation = inputTransformation,
        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
        scrollState = scrollState,
        interactionSource = interactionSource,
        textStyle = editorTextStyle(),
        cursorBrush = editorCursor(),
        decorator = decorator,
    )
}

@Composable
@ReadOnlyComposable
private fun editorTextStyle() = HbTheme.typography.body.copy(color = HbTheme.colors.textPrimary)

@Composable
@ReadOnlyComposable
private fun editorCursor() = SolidColor(HbTheme.colors.textPrimary)

/** Password keyboard without suggestions, so the IME neither shows nor learns the secret. */
private val SecretKeyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)

private class ControlledEditorBridge(private var externalText: String, private var latestSelection: TextRange) {
    /** Reported texts the owner has not yet published as [externalText], oldest first. */
    private val pendingReports = mutableListOf<String>()
    private val selectionsBeforeEdit = mutableMapOf<String, TextRange>()
    private val selectionsAfterEdit = mutableMapOf<String, TextRange>()
    var revision by mutableIntStateOf(0)
        private set
    var rejectionCheck by mutableIntStateOf(0)
        private set

    private val latestText: String get() = pendingReports.lastOrNull() ?: externalText

    fun observeInput(buffer: TextFieldBuffer, onValueChange: (String) -> Unit) {
        val updated = buffer.asCharSequence().toString()
        val original = buffer.originalText.toString()
        if (updated != original) {
            selectionsBeforeEdit[original] = buffer.originalSelection
            report(updated, buffer.selection, onValueChange)
        } else if (updated == latestText) {
            latestSelection = buffer.selection
        }
    }

    private fun report(updated: String, selection: TextRange, onValueChange: (String) -> Unit) {
        pendingReports += updated
        selectionsAfterEdit[updated] = selection
        latestSelection = selection
        onValueChange(updated)
    }

    fun reconcile(state: TextFieldState, value: String, onValueChange: (String) -> Unit) {
        val editingText = state.text.toString()
        if (value != externalText) {
            acceptExternal(state, value, editingText)
            return
        }
        if (editingText != latestText) {
            // Undo/redo bypass input transformations. A revision reconciles even a rejected undo.
            selectionsBeforeEdit[latestText] = latestSelection
            report(editingText, state.selection, onValueChange)
            revision++
            return
        }
        when {
            pendingReports.isEmpty() -> Unit

            // Edits that return to the published text need no answer from the owner.
            editingText == externalText -> settle()

            else -> {
                log.d { "controlled edit awaiting owner pending=${pendingReports.size}" }
                rejectionCheck++
            }
        }
    }

    /** Restores the published text only if the owner still has not answered the latest proposal. */
    fun restoreIfUnanswered(state: TextFieldState, value: String) {
        if (pendingReports.isEmpty() || value != externalText || state.text.toString() != latestText) return
        log.d { "controlled edit rejected; restoring published text length=${externalText.length}" }
        pendingReports.clear()
        // A late answer can still arrive: post-edit selections are kept so it restores the caret.
        state.replaceControlledText(externalText, selectionsBeforeEdit[externalText] ?: state.selection)
        latestSelection = state.selection
    }

    private fun acceptExternal(state: TextFieldState, value: String, editingText: String) {
        externalText = value
        val acknowledged = pendingReports.indexOf(value)
        if (acknowledged >= 0) {
            repeat(acknowledged + 1) { pendingReports.removeAt(0) }
            // Newer edits are still in flight and already visible; keep them.
            if (pendingReports.isNotEmpty()) return
        } else {
            pendingReports.clear()
        }
        if (editingText != value) {
            val selection = selectionsAfterEdit[value] ?: selectionsBeforeEdit[value]
                ?: ownerSelection(editingText, value, state.selection)
            state.replaceControlledText(value, selection)
        }
        latestSelection = state.selection
        settle()
    }

    private fun settle() {
        pendingReports.clear()
        selectionsBeforeEdit.clear()
        selectionsAfterEdit.clear()
    }
}

/**
 * Caret for text the owner replaced: a caret at the end of [old] stays at the end, and text the owner put before
 * [old] (a command prefix) moves the caret with the text after it; any other caret is clamped.
 */
private fun ownerSelection(old: String, new: String, selection: TextRange): TextRange {
    val shift = new.length - old.length
    return when {
        selection.collapsed && selection.end == old.length -> TextRange(new.length)
        old.isNotEmpty() && new.endsWith(old) -> TextRange(selection.start + shift, selection.end + shift)
        else -> selection
    }
}

private fun TextFieldState.replaceControlledText(value: String, restoredSelection: TextRange) {
    edit {
        replace(0, length, value)
        selection = TextRange(
            restoredSelection.start.coerceAtMost(length),
            restoredSelection.end.coerceAtMost(length),
        )
    }
}
