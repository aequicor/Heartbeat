package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme

private val log = Log.tag("DS/DateField")

/**
 * Desktop has no system date picker for Compose: a flat text field. The value receives the date once the text is a
 * calendar day and an empty value while it is not, so callers never act on a date the field does not show; out of
 * focus the field shows the value, so a caller that sets or clears it is visible at once.
 */
@Composable
internal actual fun PlatformDateField(
    value: String,
    onValueChange: (String) -> Unit,
    text: HbDateFieldText,
    modifier: Modifier,
    enabled: Boolean,
) {
    val draft = remember { DateDraft() }
    val shown = draft.shown(value)
    val isInvalid = shown.isNotEmpty() && !isHbDate(shown)
    HbColumn(modifier, gap = HbTheme.spacing.xxs) {
        HbTextField(
            value = shown,
            onValueChange = { typed -> onValueChange(draft.type(typed)) },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { draft.focus(it.hasFocus, value) }
                .semantics { if (isInvalid) error(text.invalid) },
            placeholder = text.placeholder,
            enabled = enabled,
            accessibleLabel = text.label,
        )
        if (isInvalid) {
            HbText(
                text.invalid,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
        }
    }
}

/** The text of the field: what the user types while it is focused, otherwise the caller's value. */
@Stable
private class DateDraft {
    private var draft by mutableStateOf("")
    private var isEditing by mutableStateOf(false)

    fun shown(value: String): String = if (isEditing) draft else value

    /** Keeps [typed] and returns the value to report: the date, or empty while the text is not one. */
    @HighFrequency
    fun type(typed: String): String {
        log.v { "Date draft changed: ${typed.length} chars" }
        draft = typed
        return if (isHbDate(typed)) typed else ""
    }

    /** Editing starts from the caller's [value] and ends with focus. */
    fun focus(hasFocus: Boolean, value: String) {
        if (hasFocus && !isEditing) {
            log.v { "Date editing started" }
            draft = value
        }
        isEditing = hasFocus
    }
}
