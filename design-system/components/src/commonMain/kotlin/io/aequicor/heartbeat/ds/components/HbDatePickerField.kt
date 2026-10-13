package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * A date button with an explicit empty value, wrapping a host picker that always needs a date. The picker edits a
 * draft; only confirmation reports it, including today's unchanged initial date. Dismissal keeps the caller's value,
 * and a changed value or disabled field closes the draft so a stale native selection cannot overwrite a reset.
 */
@Composable
internal fun HbDatePickerField(
    value: String,
    onValueChange: (String) -> Unit,
    text: HbDateFieldText,
    today: () -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    picker: @Composable (value: String, onValueChange: (String) -> Unit) -> Unit,
) {
    var draft by remember(value, enabled) { mutableStateOf<String?>(null) }
    val shown = value.ifEmpty { text.placeholder }
    HbButton(
        text = shown,
        onClick = { draft = value.takeIf(::isHbDate) ?: today() },
        modifier = modifier.semantics { contentDescription = "${text.label}: $shown" },
        style = HbButtonStyle.Secondary,
        enabled = enabled,
    )
    val selected = draft ?: return
    HbDialog(
        title = text.label,
        onDismissRequest = { draft = null },
        actions = {
            HbButton(text.cancel, { draft = null }, style = HbButtonStyle.Ghost)
            HbButton(
                text.confirm,
                {
                    draft = null
                    onValueChange(selected)
                },
                enabled = isHbDate(selected),
            )
        },
    ) { picker(selected) { draft = it } }
}
