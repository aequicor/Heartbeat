package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ResourceFileUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ResourceInventoryUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ResourceKindUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ResourceOriginUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ResourceStateUi
import kotlinx.collections.immutable.toImmutableList

private val PreviewInventory = ResourceInventoryUi(
    ResourceStateUi.entries.map {
        ResourceFileUi(
            ResourceKindUi.Skill,
            ResourceOriginUi.Project,
            ".agents/skills/${it.name}/SKILL.md",
            "Проверка проекта",
            it,
            "Инструкция для работы с проектом",
        )
    }.toImmutableList(),
    external = 1,
    isTruncated = false,
)

@Preview
@Composable
private fun ResourcesLight() {
    HbTheme(darkTheme = false) { ResourceContent(PreviewInventory, false, true, {}) }
}

@Preview
@Composable
private fun ResourcesDark() {
    HbTheme(darkTheme = true) { ResourceContent(PreviewInventory, false, true, {}) }
}
