package io.aequicor.heartbeat.ds.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbComposerAction
import io.aequicor.heartbeat.ds.components.HbComposerMultiSelect
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbMultiSelectLabels
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.toImmutableList

/** Interactive search, empty search results, disabled rows and long labels; keyboard and pointer states are live. */
@Composable
internal fun MultiSelectExample() {
    var selected by remember { mutableStateOf(setOf("code")) }
    var isExpanded by remember { mutableStateOf(false) }
    val actions = listOf(
        HbComposerAction("code", "Разработка", "Код и тесты", isSelected = "code" in selected),
        HbComposerAction("review", "Ревью изменений и проверка архитектуры проекта", isSelected = "review" in selected),
        HbComposerAction("disabled", "Недоступный харнесс", isEnabled = false),
    ).toImmutableList()
    val labels = HbMultiSelectLabels(
        "Харнессы",
        "Найти харнесс",
        "Очистить поиск",
        "Ничего не найдено",
        "Выбрано: ${selected.size}",
        "Очистить",
        "Готово",
    )
    HbFlowRow {
        HbComposerMultiSelect(
            "Харнессы · ${selected.size}",
            actions,
            labels,
            isExpanded,
            { isExpanded = it },
            { selected = if (it in selected) selected - it else selected + it },
            { selected = emptySet() },
            icon = HbIcons.Layers,
        )
        HbComposerMultiSelect("Недоступно", actions, labels, false, {}, {}, {}, enabled = false)
    }
}

@Preview
@Composable
private fun MultiSelectLightPreview() {
    HbTheme(darkTheme = false) { MultiSelectExample() }
}

@Preview
@Composable
private fun MultiSelectDarkPreview() {
    HbTheme(darkTheme = true) { MultiSelectExample() }
}
