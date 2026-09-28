package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.TextRange
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
    obscured: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val state = rememberTextFieldState(value)
    val scrollState = rememberScrollState()
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
        outputTransformation = if (obscured) {
            OutputTransformation {
                replace(0, length, "•".repeat(length))
            }
        } else {
            null
        },
        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
        scrollState = scrollState,
        interactionSource = interactionSource,
        textStyle = HbTheme.typography.body.copy(color = HbTheme.colors.textPrimary),
        cursorBrush = SolidColor(HbTheme.colors.textPrimary),
        decorator = { innerTextField ->
            Box(contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
                if (editingText.isEmpty()) HbText(placeholder, color = HbTheme.colors.textSecondary)
                innerTextField()
            }
        },
    )
}

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
            val selection = selectionsAfterEdit[value] ?: selectionsBeforeEdit[value] ?: state.selection
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

private fun TextFieldState.replaceControlledText(value: String, restoredSelection: TextRange) {
    edit {
        replace(0, length, value)
        selection = TextRange(
            restoredSelection.start.coerceAtMost(length),
            restoredSelection.end.coerceAtMost(length),
        )
    }
}
