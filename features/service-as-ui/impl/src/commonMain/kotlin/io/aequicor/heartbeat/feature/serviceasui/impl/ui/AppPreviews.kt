package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app.AppHostState
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.card.CardPhase
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.card.CardState
import kotlinx.collections.immutable.persistentMapOf

@Preview(name = "Mini-app entry · light", widthDp = 420, heightDp = 140)
@Composable
private fun AppCardLightPreview() {
    HbTheme(darkTheme = false) { AppCardPreview() }
}

@Preview(name = "Mini-app entry · dark", widthDp = 420, heightDp = 140)
@Composable
private fun AppCardDarkPreview() {
    HbTheme(darkTheme = true) { AppCardPreview() }
}

@Preview(name = "Mini-app workspace · light", widthDp = 1280, heightDp = 900)
@Composable
private fun AppWorkspaceLightPreview() {
    HbTheme(darkTheme = false) { WorkspacePreview() }
}

@Preview(name = "Mini-app workspace · dark compact", widthDp = 420, heightDp = 800)
@Composable
private fun AppWorkspaceDarkPreview() {
    HbTheme(darkTheme = true) { WorkspacePreview() }
}

@Composable
private fun AppCardPreview() {
    CardView(
        CardState(
            phase = CardPhase.Drawn,
            title = "Orbit Gym",
            description = "Программа и история тренировок",
            isApp = true,
            isOpenEnabled = true,
        ),
        persistentMapOf(),
    )
}

@Composable
private fun WorkspacePreview() {
    AppHostView(AppHostState(title = "Orbit Gym", isExpanded = true), hasDocument = false, {}) {
        HbEmptyState("Начать тренировку", Modifier.fillMaxSize(), "Программа, подходы и прогресс в одном месте")
    }
}
