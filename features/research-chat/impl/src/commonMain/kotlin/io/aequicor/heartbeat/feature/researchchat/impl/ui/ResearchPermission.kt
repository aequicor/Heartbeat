package io.aequicor.heartbeat.feature.researchchat.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchPermissionKind
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchPermissionUi
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenIntent
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.canSubmit
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.changeSelection
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.decision

/** Structured input remains local until an explicit offered action submits it. */
@Composable
internal fun ResearchPermission(permission: ResearchPermissionUi, onIntent: (ResearchScreenIntent) -> Unit) {
    var selected by remember(permission) { mutableStateOf(emptySet<String>()) }
    var text by remember(permission) { mutableStateOf("") }
    HbColumn {
        HbText(permission.title)
        permission.description?.let { HbText(it) }
        if (permission.kind == ResearchPermissionKind.FreeText) {
            HbTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = permission.placeholder.orEmpty(),
                singleLine = !permission.isMultiline,
            )
        }
        permission.choices.forEach { (id, title) ->
            HbSwitch(
                checked = id in selected,
                label = title,
                onCheckedChange = { checked ->
                    selected = permission.changeSelection(selected, id, checked)
                },
            )
        }
        permission.options.forEach { option ->
            HbButton(
                option.title,
                { onIntent(permission.decision(option, selected, text)) },
                enabled = permission.canSubmit(option, selected),
            )
        }
    }
}
