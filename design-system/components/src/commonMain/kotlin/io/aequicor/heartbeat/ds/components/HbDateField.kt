package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier

/**
 * A calendar date kept as `YYYY-MM-DD`. Each host picks dates its own way: Android opens the framework
 * `DatePickerDialog` from a button that shows the date, iOS opens a compact `UIDatePicker` with confirmation from a
 * button that shows the date or [placeholder], and desktop keeps a flat
 * [HbTextField] that passes only calendar dates: while focused it shows what the user types and reports an empty value
 * until the text is a date, with [invalidText] under it; out of focus it shows [value]. [label] names the field for
 * screen readers; [placeholder] stands in for a missing date and spells the expected format. [cancelText] and
 * [confirmText] name the actions of the iOS draft picker; the caller supplies localized labels.
 */
@Composable
public fun HbDateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    invalidText: String,
    cancelText: String,
    confirmText: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val text = HbDateFieldText(label, placeholder, invalidText, cancelText, confirmText)
    PlatformDateField(value, onValueChange, text, modifier, enabled)
}

/** Labels of a date field. */
@Immutable
internal data class HbDateFieldText(
    val label: String,
    val placeholder: String,
    val invalid: String,
    val cancel: String,
    val confirm: String,
)

/** A date as its year, month 1..12 and day of month; [toString] writes `YYYY-MM-DD`. */
internal data class HbDateParts(val year: Int, val month: Int, val day: Int) {
    override fun toString(): String =
        "${year.toString().padStart(YEAR_DIGITS, '0')}-${month.toString().padStart(2, '0')}-" +
            day.toString().padStart(2, '0')
}

/** Whether [text] is a calendar date written as `YYYY-MM-DD`. */
internal fun isHbDate(text: String): Boolean = hbDateParts(text) != null

/** The parts of a `YYYY-MM-DD` calendar date, or null for any other text. */
internal fun hbDateParts(text: String): HbDateParts? {
    val match = IsoDate.matchEntire(text) ?: return null
    val (year, month, day) = match.destructured.toList().map { it.toInt() }
    val isLeap = year % LEAP_EVERY == 0 && (year % CENTURY != 0 || year % LEAP_CENTURY == 0)
    val days = when (month) {
        2 -> if (isLeap) LEAP_FEBRUARY else FEBRUARY
        4, 6, 9, 11 -> SHORT_MONTH
        else -> LONG_MONTH
    }
    return HbDateParts(year, month, day).takeIf { month in 1..MONTHS && day in 1..days }
}

@Composable
internal expect fun PlatformDateField(
    value: String,
    onValueChange: (String) -> Unit,
    text: HbDateFieldText,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
)

private val IsoDate = Regex("""(\d{4})-(\d{2})-(\d{2})""")
private const val YEAR_DIGITS = 4
private const val MONTHS = 12
private const val LEAP_EVERY = 4
private const val CENTURY = 100
private const val LEAP_CENTURY = 400
private const val FEBRUARY = 28
private const val LEAP_FEBRUARY = 29
private const val SHORT_MONTH = 30
private const val LONG_MONTH = 31
