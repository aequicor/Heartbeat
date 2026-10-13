package io.aequicor.heartbeat.ds.components

import android.app.DatePickerDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.core.logging.Log
import java.util.Calendar

private val log = Log.tag("DS/DateField")

/** The framework date picker dialog, opened from a button that shows the chosen date; leaving closes the dialog. */
@Composable
internal actual fun PlatformDateField(
    value: String,
    onValueChange: (String) -> Unit,
    text: HbDateFieldText,
    modifier: Modifier,
    enabled: Boolean,
) {
    val context = LocalContext.current
    val onDate by rememberUpdatedState(onValueChange)
    var dialog by remember { mutableStateOf<DatePickerDialog?>(null) }
    DisposableEffect(Unit) { onDispose { dialog?.dismiss() } }
    val shown = value.ifEmpty { text.placeholder }
    HbButton(
        text = shown,
        onClick = {
            log.i { "Date picker opened" }
            val start = hbDateParts(value) ?: today()
            dialog = DatePickerDialog(
                context,
                { _, year, month, day -> onDate(HbDateParts(year, month + 1, day).toString()) },
                start.year,
                start.month - 1,
                start.day,
            ).apply {
                setOnDismissListener { dialog = null }
                show()
            }
        },
        modifier = modifier.semantics { contentDescription = "${text.label}: $shown" },
        style = HbButtonStyle.Secondary,
        enabled = enabled,
    )
}

private fun today(): HbDateParts {
    val calendar = Calendar.getInstance()
    return HbDateParts(
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH) + 1,
        calendar.get(Calendar.DAY_OF_MONTH),
    )
}
