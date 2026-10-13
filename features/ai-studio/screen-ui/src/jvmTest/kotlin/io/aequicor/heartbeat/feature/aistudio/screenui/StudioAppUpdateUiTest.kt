package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.components.HbWindowChrome
import io.aequicor.heartbeat.ds.components.HbWindowChromeProvider
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AppUpdateFailureUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AppUpdateStageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AppUpdateUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.OrganismStatusUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.OrganismUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SubSessionKindUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SubSessionStateUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SubSessionUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `an active zygote requires confirmation without an ordinary running turn`() =
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            render(organismWorkspace(SubSessionKindUi.Zygote, SubSessionStateUi.Working), events)

            assertUpdateRequiresConfirmation(events)
        }

    @Test
    fun `the compact drawer confirms an update while an unseen organism judge works`() =
        runSkikoComposeUiTest(size = Size(420f, 800f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            val state = organismWorkspace(SubSessionKindUi.Complaint, SubSessionStateUi.Judging)
            render(state.copy(sidebar = state.sidebar.copy(isDrawerOpen = true)), events)

            onNodeWithTag("studio-drawer").assertIsDisplayed()
            assertUpdateRequiresConfirmation(events)
        }

    private fun SkikoComposeUiTest.assertUpdateRequiresConfirmation(events: List<AiStudioScreenIntent>) {
        onNodeWithTag("sidebar-app-update").performClick()
        onNodeWithTag("app-update-confirm-dialog").assertIsDisplayed()
        assertEquals(emptyList(), events.filterIsInstance<AiStudioScreenIntent.InstallAppUpdate>())

        onNodeWithTag("app-update-confirm").performClick()
        assertEquals(1, events.count { it == AiStudioScreenIntent.InstallAppUpdate })
        onNodeWithTag("app-update-confirm-dialog").assertDoesNotExist()
    }

    private fun organismWorkspace(kind: SubSessionKindUi, phase: SubSessionStateUi) =
        desktopAuditWorkspace(isEmpty = true).copy(
            appUpdate = offer,
            organisms = persistentMapOf(
                "background-organism" to OrganismUi(
                    OrganismStatusUi.Developing,
                    persistentListOf(SubSessionUi("agent", kind, "Agent", phase)),
                ),
            ),
        )

    @Test
    fun `a running installation shows its progress instead of a button`() =
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            val downloading = offer.copy(stage = AppUpdateStageUi.Downloading, percent = 45)
            render(desktopAuditWorkspace(isEmpty = true).copy(appUpdate = downloading), events)

            onNodeWithTag("sidebar-app-update-status").assertIsDisplayed().assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(0.45f, 0f..1f),
                ),
            )
            onNodeWithTag("sidebar-app-update").assertDoesNotExist()
        }

    @Test
    fun `an installation that cannot replace itself opens the release page`() =
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            val opened = mutableListOf<String>()
            val manual = offer.copy(stage = AppUpdateStageUi.ManualInstall)
            render(desktopAuditWorkspace(isEmpty = true).copy(appUpdate = manual), events) { uri -> opened += uri }

            onNodeWithTag("sidebar-app-update").performClick()

            assertEquals(listOf(offer.pageUrl), opened)
            assertEquals(emptyList(), events.filterIsInstance<AiStudioScreenIntent.InstallAppUpdate>())
        }

    @Test
    fun `the offer never squeezes the header controls beside the macOS window buttons`() =
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val failed = offer.copy(failure = AppUpdateFailureUi.PreviousAttemptFailed)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    HbTheme(darkTheme = false, dimensions = HbDimensions.Desktop) {
                        HbWindowChromeProvider(HbWindowChrome(44.dp, 96.dp), Modifier.fillMaxSize()) {
                            AiStudioContent(
                                desktopAuditWorkspace(isEmpty = true).copy(appUpdate = failed),
                                {},
                                StudioExits(onBack = {}, onOpenToggles = {}),
                            )
                        }
                    }
                }
            }
            val controlSize = HbDimensions.Desktop.navigationRowHeight
            onNodeWithTag("sidebar-new-session").assertWidthIsEqualTo(controlSize)
            onNodeWithTag("rail-sidebar").assertWidthIsEqualTo(controlSize)
            val update = onNodeWithTag("sidebar-app-update").assertIsDisplayed().getUnclippedBoundsInRoot()
            val newSession = onNodeWithTag("sidebar-new-session").getUnclippedBoundsInRoot()
            assertTrue(update.right <= newSession.left, "The offer must end before the header controls")
        }

    @Test
    fun `without an offer the header has no update button`() = runSkikoComposeUiTest(size = Size(1280f, 800f)) {
        render(desktopAuditWorkspace(isEmpty = true), mutableListOf())

        onNodeWithTag("sidebar-app-update").assertDoesNotExist()
    }

    @Test
    fun `browser hosts hide every native update stage at wide and drawer widths`() {
        for (width in listOf(420f, 1280f)) {
            for (stage in AppUpdateStageUi.entries) {
                runSkikoComposeUiTest(size = Size(width, 800f)) {
                    val workspace = desktopAuditWorkspace(isEmpty = true)
                    val state = workspace.copy(
                        appUpdate = offer.copy(stage = stage),
                        sidebar = workspace.sidebar.copy(isDrawerOpen = true),
                    )
                    setContent {
                        HbTheme {
                            AiStudioContent(
                                state,
                                {},
                                StudioExits(),
                                host = StudioHostCapabilities(isAppUpdateAvailable = false),
                            )
                        }
                    }
                    onNodeWithTag("sidebar-app-update").assertDoesNotExist()
                    onNodeWithTag("app-update-confirm").assertDoesNotExist()
                }
            }
        }
    }

    private fun SkikoComposeUiTest.render(
        state: AiStudioScreenState,
        events: MutableList<AiStudioScreenIntent>,
        openUri: (String) -> Unit = {},
    ) {
        val links = object : UriHandler {
            override fun openUri(uri: String) = openUri(uri)
        }
        setContent {
            CompositionLocalProvider(LocalUriHandler provides links) {
                HbTheme(darkTheme = false) {
                    AiStudioContent(state, { events += it }, StudioExits(onBack = {}, onOpenToggles = {}))
                }
            }
        }
    }
}
