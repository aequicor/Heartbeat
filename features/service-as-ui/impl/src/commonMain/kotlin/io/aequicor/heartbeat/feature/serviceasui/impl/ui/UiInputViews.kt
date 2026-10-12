package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.components.HbActivityIndicator
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbChip
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbMenu
import io.aequicor.heartbeat.ds.components.HbMenuItem
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.NativeWidgetUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiNodeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.Res
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_busy
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_cancel
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_choose_file
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_choose_folder
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_continue
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_date_hint
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_date_invalid
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_not_chosen
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_select
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.stringResource

private val log = Log.tag("ServiceUiInputs")

@Composable
internal fun InputView(node: UiNodeUi.Input, onIntent: (SurfaceIntent) -> Unit, modifier: Modifier = Modifier) {
    when (node) {
        is UiNodeUi.Button -> ButtonView(node, onIntent, modifier)

        is UiNodeUi.TextField -> Labeled(node.label, modifier) {
            HbTextField(
                value = node.value,
                onValueChange = { onIntent(SurfaceIntent.Input(node.stateKey, JsonPrimitive(it))) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = node.placeholder.orEmpty(),
                singleLine = !node.isMultiline,
                accessibleLabel = node.label ?: node.placeholder.orEmpty(),
            )
        }

        // The switch carries the label for screen readers; the visible text repeats it for sighted users only.
        is UiNodeUi.Toggle -> HbRow(modifier.fillMaxWidth(), gap = HbTheme.spacing.m) {
            HbText(node.label, Modifier.weight(1f).clearAndSetSemantics {})
            HbSwitch(
                checked = node.isChecked,
                onCheckedChange = { onIntent(SurfaceIntent.Input(node.stateKey, JsonPrimitive(it))) },
                label = node.label,
            )
        }

        is UiNodeUi.Select -> Labeled(node.label, modifier) { SelectView(node, onIntent) }

        is UiNodeUi.Native -> Labeled(node.label, modifier) { NativeView(node, onIntent) }
    }
}

/** A button, or an icon button named by its label when the spec gives a known icon; [UiNodeUi.Button.confirm] first. */
@Composable
private fun ButtonView(node: UiNodeUi.Button, onIntent: (SurfaceIntent) -> Unit, modifier: Modifier = Modifier) {
    var isConfirming by remember(node.key) { mutableStateOf(false) }
    val press = { if (node.confirm == null) onIntent(SurfaceIntent.Press(node.press)) else isConfirming = true }
    val icon = iconOf(node.icon)
    val isEnabled = node.isEnabled && !node.isBusy
    HbRow(modifier, gap = HbTheme.spacing.s) {
        if (icon == null) {
            HbButton(node.label, press, style = node.style.hb(), enabled = isEnabled, size = HbButtonSize.Small)
        } else {
            HbIconButton(icon, node.label, press, enabled = isEnabled)
        }
        if (node.isBusy) HbActivityIndicator(contentDescription = stringResource(Res.string.service_ui_busy))
    }
    val question = node.confirm
    if (isConfirming && question != null) {
        HbDialog(
            title = node.label,
            onDismissRequest = { isConfirming = false },
            actions = {
                HbButton(
                    stringResource(Res.string.service_ui_cancel),
                    { isConfirming = false },
                    style = HbButtonStyle.Ghost,
                )
                HbButton(
                    node.label.ifBlank { stringResource(Res.string.service_ui_continue) },
                    {
                        isConfirming = false
                        onIntent(SurfaceIntent.Press(node.press))
                    },
                    style = node.style.hb().takeIf { it == HbButtonStyle.Danger } ?: HbButtonStyle.Primary,
                )
            },
        ) { HbText(question) }
    }
}

@Composable
private fun SelectView(node: UiNodeUi.Select, onIntent: (SurfaceIntent) -> Unit) {
    var isExpanded by remember(node.key) { mutableStateOf(false) }
    val shown = node.options.firstOrNull { it.value == node.selected }?.label
        ?: stringResource(Res.string.service_ui_select)
    val items = remember(node.options, node.selected) {
        node.options.map { HbMenuItem(it.value, it.label, isChecked = it.value == node.selected) }.toImmutableList()
    }
    Box {
        HbChip(
            label = shown,
            onClick = { isExpanded = true },
            accessibleLabel = listOfNotNull(node.label, shown).joinToString(": "),
        )
        HbMenu(
            items = items,
            isExpanded = isExpanded,
            onDismiss = { isExpanded = false },
            onItem = { value ->
                isExpanded = false
                onIntent(SurfaceIntent.Input(node.stateKey, JsonPrimitive(value)))
            },
            label = node.label ?: shown,
        )
    }
}

@Composable
private fun NativeView(node: UiNodeUi.Native, onIntent: (SurfaceIntent) -> Unit) {
    when (node.widget) {
        NativeWidgetUi.Date -> DateField(
            value = node.value,
            label = node.label ?: stringResource(Res.string.service_ui_date_hint),
            onDateChange = { onIntent(SurfaceIntent.Input(node.stateKey, JsonPrimitive(it))) },
        )

        NativeWidgetUi.File, NativeWidgetUi.Folder -> HbRow(gap = HbTheme.spacing.s) {
            HbText(
                node.value.ifBlank { stringResource(Res.string.service_ui_not_chosen) },
                Modifier.weight(1f),
                color = if (node.value.isBlank()) HbTheme.colors.textSecondary else HbTheme.colors.textPrimary,
            )
            val label = if (node.widget == NativeWidgetUi.File) {
                Res.string.service_ui_choose_file
            } else {
                Res.string.service_ui_choose_folder
            }
            HbButton(
                stringResource(label),
                { onIntent(SurfaceIntent.Pick(node.stateKey, node.widget, node.extensions)) },
                style = HbButtonStyle.Secondary,
                size = HbButtonSize.Small,
            )
        }
    }
}

/**
 * A date typed as YYYY-MM-DD. While focused the field shows what the user types, and the state receives the date once
 * it is valid and an empty value while it is not, so no action works with a date the field does not show. Out of
 * focus the field shows the state, so actions that set or clear the date are visible at once.
 */
@Composable
internal fun DateField(value: String, label: String, onDateChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val draft = remember { DateDraft() }
    val text = draft.shown(value)
    val isInvalid = text.isNotEmpty() && !isIsoDate(text)
    val invalid = stringResource(Res.string.service_ui_date_invalid)
    HbColumn(modifier, gap = HbTheme.spacing.xxs) {
        HbTextField(
            value = text,
            onValueChange = { typed -> onDateChange(draft.type(typed)) },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { draft.focus(it.hasFocus, value) }
                .semantics { if (isInvalid) error(invalid) },
            placeholder = stringResource(Res.string.service_ui_date_hint),
            accessibleLabel = label,
        )
        if (isInvalid) {
            HbText(
                invalid,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
        }
    }
}

/** The text of a date field: what the user types while it is focused, otherwise the state's value. */
@Stable
private class DateDraft {
    private var draft by mutableStateOf("")
    private var isEditing by mutableStateOf(false)

    fun shown(value: String): String = if (isEditing) draft else value

    /** Keeps [typed] and returns what the state receives: the date, or empty while it is not one. */
    @HighFrequency
    fun type(typed: String): String {
        log.v { "Date draft changed: ${typed.length} chars" }
        draft = typed
        return if (isIsoDate(typed)) typed else ""
    }

    /** Editing starts from the state's [value] and ends with focus. */
    fun focus(hasFocus: Boolean, value: String) {
        if (hasFocus && !isEditing) {
            log.v { "Date editing started" }
            draft = value
        }
        isEditing = hasFocus
    }
}

/** Whether [text] is a calendar date written as YYYY-MM-DD. */
internal fun isIsoDate(text: String): Boolean {
    val match = IsoDate.matchEntire(text) ?: return false
    val (year, month, day) = match.destructured.toList().map { it.toInt() }
    val isLeap = year % LEAP_EVERY == 0 && (year % CENTURY != 0 || year % LEAP_CENTURY == 0)
    val days = when (month) {
        2 -> if (isLeap) LEAP_FEBRUARY else FEBRUARY
        4, 6, 9, 11 -> SHORT_MONTH
        else -> LONG_MONTH
    }
    return month in 1..MONTHS && day in 1..days
}

@Composable
private fun Labeled(label: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xxs) {
        label?.let { HbText(it, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary) }
        content()
    }
}

private val IsoDate = Regex("""(\d{4})-(\d{2})-(\d{2})""")
private const val MONTHS = 12
private const val LEAP_EVERY = 4
private const val CENTURY = 100
private const val LEAP_CENTURY = 400
private const val FEBRUARY = 28
private const val LEAP_FEBRUARY = 29
private const val SHORT_MONTH = 30
private const val LONG_MONTH = 31
