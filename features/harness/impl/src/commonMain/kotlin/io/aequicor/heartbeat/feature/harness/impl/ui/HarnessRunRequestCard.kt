package io.aequicor.heartbeat.feature.harness.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbChoiceRow
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessDetailIntent
import io.aequicor.heartbeat.feature.harness.impl.presentation.RequestKindUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.RunRequestUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.canSubmit
import io.aequicor.heartbeat.feature.harness.impl.presentation.changeSelection
import io.aequicor.heartbeat.feature.harness.impl.resources.Res
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_run_request_cell
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_run_request_choices
import org.jetbrains.compose.resources.stringResource

/**
 * A request of a cell of the organism a run owns. The organism has no chat, so this card is where the user answers.
 * A choice or a text stays local until an offered action submits it; the card disappears when the cell no longer asks.
 */
@Composable
internal fun RunRequestCard(
    request: RunRequestUi,
    onIntent: (HarnessDetailIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember(request.key) { mutableStateOf(emptySet<String>()) }
    var text by remember(request.key) { mutableStateOf("") }
    HbColumn(
        modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.m, vertical = HbTheme.spacing.s)
            .testTag("harness-run-request-${request.key}"),
        gap = HbTheme.spacing.s,
    ) {
        HbText(
            stringResource(Res.string.harness_run_request_cell, request.cellName),
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
        HbText(request.title, style = HbTheme.typography.label)
        request.description?.let { HbText(it, color = HbTheme.colors.textSecondary) }
        if (request.kind == RequestKindUi.FreeText) {
            HbTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().testTag("harness-run-request-text-${request.key}"),
                placeholder = request.placeholder.orEmpty(),
                singleLine = !request.isMultiline,
                accessibleLabel = request.title,
            )
        }
        MultiChoiceHint(request, selected.size)
        val isSingle = request.kind == RequestKindUi.SingleChoice
        if (request.choices.isNotEmpty()) {
            HbColumn(if (isSingle) Modifier.selectableGroup() else Modifier, gap = HbTheme.spacing.none) {
                request.choices.forEach { choice ->
                    val isSelected = choice.id in selected
                    HbChoiceRow(
                        label = choice.title,
                        selected = isSelected,
                        isMultiple = !isSingle,
                        onClick = {
                            // A radio button selects; only a checkbox toggles off.
                            selected = request.changeSelection(selected, choice.id, isSingle || !isSelected)
                        },
                        modifier = Modifier.testTag("harness-run-request-choice-${request.key}-${choice.id}"),
                    )
                }
            }
        }
        HbFlowRow {
            request.options.forEach { option ->
                HbButton(
                    option.title,
                    { onIntent(HarnessDetailIntent.DecideRequest(request, option.id, selected, text)) },
                    Modifier.testTag("harness-run-request-option-${request.key}-${option.id}"),
                    enabled = request.canSubmit(option, selected),
                    style = if (option.isSkip) HbButtonStyle.Secondary else HbButtonStyle.Primary,
                    size = HbButtonSize.Small,
                )
            }
        }
    }
}

/** Explains both incomplete and excessive selection without relying on the engine's question text. */
@Composable
private fun MultiChoiceHint(request: RunRequestUi, selected: Int) {
    if (request.kind != RequestKindUi.MultiChoice) return
    HbText(
        stringResource(Res.string.harness_run_request_choices, request.min, request.max, selected),
        style = HbTheme.typography.caption,
        color = HbTheme.colors.textSecondary,
    )
}
