package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ProjectTrustNoticeUi
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class StudioProjectTrustUiTest {
    @Test
    fun `the focused chat asks whether its project may load its engine configuration`() = runSkikoComposeUiTest {
        val intents = mutableListOf<AiStudioScreenIntent>()
        val notice = ProjectTrustNoticeUi("web", "pi", "Web", "Pi")
        val state = desktopAuditWorkspace(isEmpty = false).copy(projectTrust = notice)
        val exits = StudioExits(onBack = {}, onOpenToggles = {})
        setContent { HbTheme { AiStudioContent(state, { intents += it }, exits) } }

        onNodeWithTag("studio-project-trust-content").performClick()
        onNodeWithTag("studio-project-trust-trusted").performClick()
        runOnIdle {
            assertEquals(
                listOf<AiStudioScreenIntent>(
                    AiStudioScreenIntent.DecideProjectTrust("web", "pi", isTrusted = false),
                    AiStudioScreenIntent.DecideProjectTrust("web", "pi", isTrusted = true),
                ),
                intents.filterIsInstance<AiStudioScreenIntent.DecideProjectTrust>(),
            )
        }
    }
}
