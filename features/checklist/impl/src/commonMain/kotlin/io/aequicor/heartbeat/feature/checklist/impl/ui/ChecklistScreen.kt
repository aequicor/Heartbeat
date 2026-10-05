package io.aequicor.heartbeat.feature.checklist.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbChoiceRow
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.checklist.impl.presentation.store.ChecklistFieldUi
import io.aequicor.heartbeat.feature.checklist.impl.presentation.store.ChecklistPhaseUi
import io.aequicor.heartbeat.feature.checklist.impl.presentation.store.ChecklistScreenIntent
import io.aequicor.heartbeat.feature.checklist.impl.presentation.store.ChecklistScreenState
import io.aequicor.heartbeat.feature.checklist.impl.resources.Res
import io.aequicor.heartbeat.feature.checklist.impl.resources.checklist_complete
import io.aequicor.heartbeat.feature.checklist.impl.resources.checklist_completed
import io.aequicor.heartbeat.feature.checklist.impl.resources.checklist_failed
import io.aequicor.heartbeat.feature.checklist.impl.resources.checklist_pending
import io.aequicor.heartbeat.feature.checklist.impl.resources.checklist_range
import io.aequicor.heartbeat.feature.checklist.impl.resources.checklist_required
import io.aequicor.heartbeat.feature.checklist.impl.resources.checklist_retry
import io.aequicor.heartbeat.feature.checklist.impl.resources.checklist_retry_delivery
import io.aequicor.heartbeat.feature.checklist.impl.resources.checklist_saving
import io.aequicor.heartbeat.feature.checklist.impl.resources.checklist_superseded
import org.jetbrains.compose.resources.stringResource

/** An inline card, with no screen chrome or nested scrolling surface. */
@Composable
internal fun ChecklistScreen(
    state: ChecklistScreenState,
    onIntent: (ChecklistScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier.fillMaxWidth().padding(HbTheme.spacing.m).testTag("checklist"), gap = HbTheme.spacing.m) {
        HbText(state.title, style = HbTheme.typography.label)
        state.fields.forEach { field ->
            ChecklistField(field, state.phase == ChecklistPhaseUi.Open, onIntent)
        }
        when {
            state.hasFailed -> {
                HbText(stringResource(Res.string.checklist_failed))
                HbButton(stringResource(Res.string.checklist_retry), { onIntent(ChecklistScreenIntent.Retry) })
            }

            state.isSaving -> HbText(stringResource(Res.string.checklist_saving))

            state.phase == ChecklistPhaseUi.Superseded -> HbText(stringResource(Res.string.checklist_superseded))

            state.isDeliveryFailed -> HbButton(
                stringResource(Res.string.checklist_retry_delivery),
                { onIntent(ChecklistScreenIntent.RetryDelivery) },
            )

            state.isDeliveryPending -> HbText(stringResource(Res.string.checklist_pending))

            state.phase == ChecklistPhaseUi.Completed -> HbText(stringResource(Res.string.checklist_completed))
        }
        if (state.phase == ChecklistPhaseUi.Open && !state.isAutomatic) {
            HbButton(
                stringResource(Res.string.checklist_complete),
                { onIntent(ChecklistScreenIntent.Complete) },
                modifier = Modifier.testTag("checklist-complete"),
                enabled = state.isCompletionAllowed && !state.hasFailed,
            )
        }
    }
}

@Composable
private fun ChecklistField(field: ChecklistFieldUi, enabled: Boolean, onIntent: (ChecklistScreenIntent) -> Unit) {
    HbColumn(gap = HbTheme.spacing.s) {
        HbText(field.title, style = HbTheme.typography.label)
        if (field.isRequired) HbText(stringResource(Res.string.checklist_required), style = HbTheme.typography.caption)
        if (field.isMultiple) {
            HbText(
                stringResource(Res.string.checklist_range, field.min, field.max),
                style = HbTheme.typography.caption,
            )
        }
        if (field.isText) {
            HbTextField(
                field.text,
                { onIntent(ChecklistScreenIntent.Text(field.id, it)) },
                modifier = Modifier.fillMaxWidth().testTag("checklist-text-${field.id}"),
                enabled = enabled,
                singleLine = false,
                accessibleLabel = field.title,
            )
        } else {
            val choicesModifier = Modifier.testTag("checklist-choices-${field.id}")
            HbColumn(
                modifier = if (field.isMultiple) choicesModifier else choicesModifier.selectableGroup(),
                gap = HbTheme.spacing.s,
            ) {
                field.choices.forEach { choice ->
                    val isSelected = choice.id in field.selected
                    HbChoiceRow(
                        choice.title,
                        isSelected,
                        field.isMultiple,
                        { onIntent(ChecklistScreenIntent.Choice(field.id, choice.id)) },
                        modifier = Modifier.testTag("checklist-choice-${field.id}-${choice.id}"),
                        enabled = enabled && (!field.isMultiple || isSelected || field.selected.size < field.max),
                    )
                }
            }
        }
    }
}
