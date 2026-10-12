package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserPhase
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenState
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserTabUi
import kotlinx.collections.immutable.persistentListOf

@Preview(widthDp = 420, heightDp = 360)
@Composable
private fun BrowserTabsLightPreview() {
    HbTheme(darkTheme = false, dimensions = HbDimensions.Desktop) {
        BrowserScreenContent(tabPreviewState(), {})
    }
}

@Preview(widthDp = 420, heightDp = 360)
@Composable
private fun BrowserTabsDarkPreview() {
    HbTheme(darkTheme = true, dimensions = HbDimensions.Desktop) {
        BrowserScreenContent(tabPreviewState().copy(isExpanded = true), {})
    }
}

private fun tabPreviewState(): BrowserScreenState = BrowserScreenState(
    phase = BrowserPhase.Ready,
    hasTabs = true,
    selectedTabId = "source",
    tabs =
        persistentListOf(
            BrowserTabUi(
                "source",
                "Источник исследования с длинным заголовком",
                isHumanControlled = false,
                hasAgent = true,
                groupId = "session-a",
            ),
            BrowserTabUi("search", "Google", false, true, groupId = "session-a"),
            BrowserTabUi("other", "Другой чат", false, true, groupId = "session-b"),
        ),
)
