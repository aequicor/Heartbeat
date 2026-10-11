package io.aequicor.heartbeat.feature.researchchat.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchPermissionKind
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchPermissionOptionUi
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchPermissionUi
import kotlinx.collections.immutable.persistentListOf

@Preview(widthDp = 420)
@Composable
private fun ResearchPermissionLightPreview() {
    HbTheme(darkTheme = false, dimensions = HbDimensions.Desktop) {
        ResearchPermission(permissionPreview(), {})
    }
}

@Preview(widthDp = 420)
@Composable
private fun ResearchPermissionDarkPreview() {
    HbTheme(darkTheme = true, dimensions = HbDimensions.Desktop) {
        ResearchPermission(
            permissionPreview()
                .copy(kind = ResearchPermissionKind.FreeText, choices = persistentListOf()),
            {},
        )
    }
}

private fun permissionPreview(): ResearchPermissionUi = ResearchPermissionUi(
    id = "source-access",
    title = "Доступ к источнику исследования",
    description = "Выберите источник для продолжения исследования.",
    options =
        persistentListOf(
            ResearchPermissionOptionUi("submit", "Продолжить", isSkip = false),
            ResearchPermissionOptionUi("skip", "Пропустить", isSkip = true),
        ),
    kind = ResearchPermissionKind.SingleChoice,
    choices =
        persistentListOf(
            "primary" to "Основной источник",
            "secondary" to "Дополнительный источник",
        ),
    min = 1,
    max = 1,
)
