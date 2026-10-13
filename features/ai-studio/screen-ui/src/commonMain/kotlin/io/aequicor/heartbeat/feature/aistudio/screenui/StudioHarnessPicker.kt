package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbComposerAction
import io.aequicor.heartbeat.ds.components.HbComposerMultiSelect
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbMultiSelectLabels
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.harness_menu_section
import io.aequicor.heartbeat.feature.aistudio.impl.resources.harness_picker_clear
import io.aequicor.heartbeat.feature.aistudio.impl.resources.harness_picker_clear_search
import io.aequicor.heartbeat.feature.aistudio.impl.resources.harness_picker_done
import io.aequicor.heartbeat.feature.aistudio.impl.resources.harness_picker_empty
import io.aequicor.heartbeat.feature.aistudio.impl.resources.harness_picker_many
import io.aequicor.heartbeat.feature.aistudio.impl.resources.harness_picker_one
import io.aequicor.heartbeat.feature.aistudio.impl.resources.harness_picker_search
import io.aequicor.heartbeat.feature.aistudio.impl.resources.harness_picker_selected
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/** Project-aware choices replace the computer label; an empty library occupies no space in the context toolbar. */
@Composable
internal fun StudioHarnessPicker(content: PaneContent, onIntent: (AiStudioScreenIntent) -> Unit) {
    if (content.harnesses.isEmpty()) return
    var isExpanded by remember(content.pane.id, content.pane.sessionId, content.project?.id) { mutableStateOf(false) }
    val selected = content.harnesses.filter { it.isSelected }
    val label = when (selected.size) {
        0 -> stringResource(Res.string.harness_menu_section)
        1 -> stringResource(Res.string.harness_picker_one, selected.single().title)
        else -> stringResource(Res.string.harness_picker_many, selected.size)
    }
    val actions = content.harnesses.map {
        HbComposerAction(it.id, it.title, it.description.takeIf(String::isNotBlank), isSelected = it.isSelected)
    }.toImmutableList()
    HbComposerMultiSelect(
        label = label,
        actions = actions,
        labels = HbMultiSelectLabels(
            accessibleLabel = stringResource(Res.string.harness_menu_section),
            search = stringResource(Res.string.harness_picker_search),
            clearSearch = stringResource(Res.string.harness_picker_clear_search),
            empty = stringResource(Res.string.harness_picker_empty),
            selected = stringResource(Res.string.harness_picker_selected, selected.size),
            clear = stringResource(Res.string.harness_picker_clear),
            done = stringResource(Res.string.harness_picker_done),
        ),
        isExpanded = isExpanded,
        onExpandedChange = { isExpanded = it },
        onAction = { id ->
            val choice = content.harnesses.first { it.id == id }
            onIntent(AiStudioScreenIntent.SelectHarness(content.pane.id, id, !choice.isSelected))
        },
        onClear = {
            selected.forEach { onIntent(AiStudioScreenIntent.SelectHarness(content.pane.id, it.id, false)) }
        },
        modifier = Modifier.testTag("harness-picker-${content.pane.id}"),
        enabled = !content.pane.isCreating,
        icon = HbIcons.Layers,
    )
}
