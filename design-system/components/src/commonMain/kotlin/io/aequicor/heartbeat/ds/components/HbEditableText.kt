package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import io.aequicor.heartbeat.ds.layouts.hbScrollbars
import io.aequicor.heartbeat.ds.theme.HbTheme

/** Controlled text with a hoisted editor scroll state, shared by fields and the composer. */
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
) {
    val state = rememberTextFieldState(value)
    val scrollState = rememberScrollState()
    val bridge = remember(state) { ControlledEditorBridge(value, state.text.toString(), state.selection) }
    // Reading text also observes rejected proposals and edits that bypass InputTransformation, such as undo.
    val editingText = state.text.toString()
    SideEffect(value, editingText, state.selection, bridge.revision) {
        bridge.reconcile(state, value, onValueChange)
    }
    BasicTextField(
        state = state,
        modifier = modifier.hbScrollbars(
            scrollState,
            orientation = if (singleLine) Orientation.Horizontal else Orientation.Vertical,
        ).padding(contentPadding),
        enabled = enabled,
        inputTransformation = InputTransformation {
            bridge.observeInput(this, onValueChange)
        },
        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
        scrollState = scrollState,
        interactionSource = interactionSource,
        textStyle = HbTheme.typography.body.copy(color = HbTheme.colors.textPrimary),
        cursorBrush = SolidColor(HbTheme.colors.textPrimary),
        decorator = { innerTextField ->
            Box(contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
                if (value.isEmpty()) HbText(placeholder, color = HbTheme.colors.textSecondary)
                innerTextField()
            }
        },
    )
}

private class ControlledEditorBridge(
    private var externalText: String,
    private var expectedText: String,
    private var expectedSelection: TextRange,
) {
    private val selectionsBeforeEdit = mutableMapOf<String, TextRange>()
    var revision by mutableIntStateOf(0)
        private set

    fun observeInput(buffer: TextFieldBuffer, onValueChange: (String) -> Unit) {
        val updated = buffer.asCharSequence().toString()
        val original = buffer.originalText.toString()
        if (updated != original) {
            selectionsBeforeEdit[original] = buffer.originalSelection
            expectedSelection = buffer.selection
            reportInput(updated, onValueChange)
        } else if (updated == expectedText) {
            expectedSelection = buffer.selection
        }
    }

    private fun reportInput(updated: String, onValueChange: (String) -> Unit) {
        expectedText = updated
        onValueChange(updated)
    }

    fun reconcile(state: TextFieldState, value: String, onValueChange: (String) -> Unit) {
        val editingText = state.text.toString()
        if (value == externalText && editingText != expectedText) {
            // Undo/redo bypass input transformations. A revision reconciles even a rejected undo.
            selectionsBeforeEdit[expectedText] = expectedSelection
            reportInput(editingText, onValueChange)
            revision++
            return
        }
        val restoredSelection = selectionsBeforeEdit[value] ?: state.selection
        externalText = value
        expectedText = value
        if (editingText != value) state.replaceControlledText(value, restoredSelection)
        expectedSelection = state.selection
        selectionsBeforeEdit.clear()
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
