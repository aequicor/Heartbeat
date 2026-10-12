package io.aequicor.heartbeat.feature.researchchat.impl.ui

import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbChoiceRow
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchPermissionKind
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchPermissionUi
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenIntent
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.canSubmit
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.changeSelection
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.decision

/**
 * Structured input remains local until an explicit offered action submits it. A single choice is a radio
 * group, a multiple choice is a set of checkboxes.
 */
@Composable
internal fun ResearchPermission(
    permission: ResearchPermissionUi,
    onIntent: (ResearchScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember(permission.id) { mutableStateOf(emptySet<String>()) }
    var text by remember(permission.id) { mutableStateOf("") }
    HbColumn(modifier) {
        HbText(permission.title)
        permission.description?.let { HbText(it) }
        if (permission.kind == ResearchPermissionKind.FreeText) {
            HbTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = permission.placeholder.orEmpty(),
                singleLine = !permission.isMultiline,
                accessibleLabel = permission.title,
            )
        }
        val isSingle = permission.kind == ResearchPermissionKind.SingleChoice
        if (permission.choices.isNotEmpty()) {
            HbColumn(if (isSingle) Modifier.selectableGroup() else Modifier, gap = HbTheme.spacing.none) {
                permission.choices.forEach { (id, title) ->
                    val isSelected = id in selected
                    HbChoiceRow(
                        label = title,
                        selected = isSelected,
                        isMultiple = !isSingle,
                        onClick = {
                            // A radio button selects; only a checkbox toggles off.
                            selected = permission.changeSelection(selected, id, isSingle || !isSelected)
                        },
                    )
                }
            }
        }
        permission.options.forEach { option ->
            HbButton(
                option.title,
                { onIntent(permission.decision(option, selected, text)) },
                enabled = permission.canSubmit(option, selected),
                style = if (option.isSkip) HbButtonStyle.Secondary else HbButtonStyle.Primary,
            )
        }
    }
}
