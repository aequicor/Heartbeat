package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AppUpdateStageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AppUpdateUi
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals

/** The sidebar header offers a newer app version; quitting for it while agents work needs a confirmation. */
@OptIn(ExperimentalTestApi::class)
class StudioAppUpdateUiTest {
    private val offer = AppUpdateUi(
        "1.4.0",
        AppUpdateStageUi.Ready,
        pageUrl = "https://github.com/aequicor/Heartbeat/releases/tag/v1.4.0",
    )

    @Test
    fun `the sidebar header installs an offered update`() = runSkikoComposeUiTest(size = Size(1280f, 800f)) {
        val events = mutableListOf<AiStudioScreenIntent>()
        render(desktopAuditWorkspace(isEmpty = true).copy(appUpdate = offer), events)

        onNodeWithTag("sidebar-app-update").assertIsDisplayed().performClick()

        assertEquals(listOf<AiStudioScreenIntent>(AiStudioScreenIntent.InstallAppUpdate), events)
    }

    @Test
    fun `an update while agents work is installed only after a confirmation`() =
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            val workspace = desktopAuditWorkspace(isEmpty = false)
            val running = persistentSetOf(workspace.sessions.first().id)
            render(workspace.copy(appUpdate = offer, running = running), events)

            onNodeWithTag("sidebar-app-update").performClick()
            onNodeWithTag("app-update-confirm-dialog").assertIsDisplayed()
            assertEquals(emptyList(), events.filterIsInstance<AiStudioScreenIntent.InstallAppUpdate>())

            onNodeWithTag("app-update-confirm").performClick()
            assertEquals(1, events.count { it == AiStudioScreenIntent.InstallAppUpdate })
            onNodeWithTag("app-update-confirm-dialog").assertDoesNotExist()
        }

    @Test
    fun `a running installation shows its progress and cannot be started again`() =
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            val downloading = offer.copy(stage = AppUpdateStageUi.Downloading, percent = 45)
            render(desktopAuditWorkspace(isEmpty = true).copy(appUpdate = downloading), events)

            // The label is localized; the percentage is the same in every language.
            onNodeWithText("45%", substring = true).assertIsDisplayed()
            onNodeWithTag("sidebar-app-update").assertIsNotEnabled()
        }

    @Test
    fun `without an offer the header has no update button`() = runSkikoComposeUiTest(size = Size(1280f, 800f)) {
        render(desktopAuditWorkspace(isEmpty = true), mutableListOf())

        onNodeWithTag("sidebar-app-update").assertDoesNotExist()
    }

    private fun SkikoComposeUiTest.render(state: AiStudioScreenState, events: MutableList<AiStudioScreenIntent>) {
        setContent {
            HbTheme(darkTheme = false) {
                AiStudioContent(state, { events += it }, StudioExits(onBack = {}, onOpenToggles = {}))
            }
        }
    }
}
