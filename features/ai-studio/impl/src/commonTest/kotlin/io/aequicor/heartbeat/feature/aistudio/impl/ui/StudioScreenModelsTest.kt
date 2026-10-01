package io.aequicor.heartbeat.feature.aistudio.impl.ui

import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ApprovalUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.EnvironmentUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ModelUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ProviderUsageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.RenameUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionConfigurationUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SidebarUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class StudioScreenModelsTest {
    private val heartbeat = ProjectUi("p", "heartbeat", EnvironmentUi.Local, "master")
    private val site = ProjectUi("q", "site", EnvironmentUi.Cloud, "main")
    private val session = SessionUi("s", "Build", projectId = "q", updatedAt = Instant.fromEpochSeconds(0))
    private val state = AiStudioScreenState(
        panes = persistentListOf(PaneUi(0, sessionId = "s"), PaneUi(1, projectId = null)),
        focusedPaneId = 0,
        projects = persistentListOf(heartbeat, site),
        sessions = persistentListOf(session),
        transcripts = persistentMapOf(
            "s" to persistentListOf(
                MessageUi.Prompt("m1", Instant.fromEpochSeconds(10), "first"),
                MessageUi.Prompt("m2", Instant.fromEpochSeconds(100), "second"),
            ),
        ),
        now = Instant.fromEpochSeconds(130),
    )

    @Test
    fun `research entry requires flag Koog and a new projectless pane`() {
        val koog = state.copy(
            isResearchEnabled = true,
            models = persistentListOf(ModelUi(state.settings.modelId, "Koog", isResearchSupported = true)),
        )
        val newChat = PaneUi(7)
        assertTrue(koog.paneContent(newChat).isResearchAvailable)
        assertEquals(false, koog.copy(isResearchEnabled = false).paneContent(newChat).isResearchAvailable)
        assertEquals(false, koog.paneContent(newChat.copy(projectId = "p")).isResearchAvailable)
        assertEquals(false, koog.paneContent(newChat.copy(sessionId = "s")).isResearchAvailable)
        assertEquals(
            false,
            koog.copy(models = persistentListOf(ModelUi(state.settings.modelId, "Other")))
                .paneContent(newChat).isResearchAvailable,
        )
        assertEquals(false, koog.copy(models = persistentListOf()).paneContent(newChat).isResearchAvailable)
    }

    @Test
    fun `every session menu item maps to its intent and unknown ids are ignored`() {
        val pinned = session.copy(isPinned = true, isUnread = true)
        assertEquals(AiStudioScreenIntent.StartRename("s", "recent:s"), sessionAction(pinned, "rename", "recent:s"))
        assertEquals(AiStudioScreenIntent.SetPinned("s", false), sessionAction(pinned, "pin", "recent:s"))
        assertEquals(AiStudioScreenIntent.SetUnread("s", false), sessionAction(pinned, "unread", "recent:s"))
        assertEquals(AiStudioScreenIntent.OpenBeside("s"), sessionAction(pinned, "beside", "recent:s"))
        assertEquals(AiStudioScreenIntent.SetArchived("s", true), sessionAction(pinned, "archive", "recent:s"))
        assertNull(sessionAction(pinned, "delete", "recent:s"))
    }

    @Test
    fun `elapsed time uses the current execution start instead of the previous prompt`() {
        val pane = state.panes.first()
        assertNull(state.paneContent(pane).elapsed)

        val running = state.copy(running = persistentSetOf("s"))
        assertNull(running.paneContent(pane).elapsed)
        val started = running.copy(runStartedAt = persistentMapOf("s" to Instant.fromEpochSeconds(125)))
        assertEquals(5.seconds, started.paneContent(pane).elapsed)
        assertEquals(0.seconds, started.copy(now = Instant.fromEpochSeconds(50)).paneContent(pane).elapsed)
        assertEquals(5.seconds, started.copy(transcripts = persistentMapOf()).paneContent(pane).elapsed)
    }

    @Test
    fun `existing chat without hydrated configuration displays its saved model instead of a global preference`() {
        val restored = state.copy(
            sessions = persistentListOf(session.copy(modelId = "saved-sol")),
            settings = state.settings.copy(modelId = "selected-luna"),
        )
        assertEquals("saved-sol", restored.paneContent(restored.panes.first()).settings.modelId)
        assertEquals("selected-luna", restored.paneContent(PaneUi(7)).settings.modelId)
        val noSelection = restored.copy(settings = state.settings.copy(modelId = ""))
        assertEquals("saved-sol", noSelection.paneContent(noSelection.panes.first()).settings.modelId)
        val noSavedModel = restored.copy(sessions = persistentListOf(session.copy(modelId = "")))
        assertEquals("selected-luna", noSavedModel.paneContent(noSavedModel.panes.first()).settings.modelId)
    }

    @Test
    fun `provider usage follows the confirmed model while chat metadata still has the previous model`() {
        val previous = ProviderUsageUi(persistentListOf(), null, "previous", null, false)
        val current = previous.copy(planName = "current")
        val changed = state.copy(
            sessions = persistentListOf(session.copy(modelId = "old-model")),
            configurations = persistentMapOf("s" to SessionConfigurationUi("new-model", "low", ApprovalUi.Ask)),
            providerUsage = persistentMapOf("old-model" to previous, "new-model" to current),
        )
        val content = changed.paneContent(changed.panes.first())
        assertEquals("new-model", content.settings.modelId)
        assertEquals(current, content.providerUsage)
    }

    @Test
    fun `two composers show their own confirmed settings and only the pending one is disabled`() {
        val pending = SessionConfigurationUi("pi-a", null, ApprovalUi.Ask, pendingOperation = "operation")
        val applied = SessionConfigurationUi("pi-b", "low", ApprovalUi.AutoApprove)
        val split = state.copy(
            panes = persistentListOf(PaneUi(0, sessionId = "s"), PaneUi(1, sessionId = "other")),
            configurations = persistentMapOf("s" to pending, "other" to applied),
            settings = state.settings.copy(
                modelId = "global-model",
                approval = ApprovalUi.AutoEdits,
                engineEfforts = persistentMapOf("pi-a" to "high"),
            ),
            running = persistentSetOf("s", "other"),
        )
        val first = split.paneContent(split.panes[0])
        val second = split.paneContent(split.panes[1])
        assertEquals("pi-a", first.settings.modelId)
        assertEquals(ApprovalUi.Ask, first.settings.approval)
        assertNull(first.settings.nativeEffort)
        assertTrue(first.isSettingPending)
        assertEquals("pi-b", second.settings.modelId)
        assertEquals(ApprovalUi.AutoApprove, second.settings.approval)
        assertEquals("low", second.settings.nativeEffort)
        assertEquals(false, second.isSettingPending)
        assertEquals("global-model", split.paneContent(PaneUi(7)).settings.modelId)
    }

    @Test
    fun `existing project chat offers only its saved model while new projects offer all local models`() {
        val models = persistentListOf(
            ModelUi("qwen", "Qwen"),
            ModelUi("codex", "Codex", isLocalProjectSupported = true),
            ModelUi("other", "Other Codex", isLocalProjectSupported = true),
        )
        val restored = state.copy(models = models, sessions = persistentListOf(session.copy(modelId = "codex")))
        assertEquals(listOf("codex"), restored.paneContent(restored.panes.first()).models.map { it.id })
        assertEquals(3, restored.paneContent(PaneUi(7)).models.size)
        assertEquals(2, restored.paneContent(PaneUi(7, projectId = "p")).models.size)
    }

    @Test
    fun `existing project chat also offers the models of its connection when the engine switches them`() {
        val models = persistentListOf(
            ModelUi("pi-a", "Pi A", isLocalProjectSupported = true, connectionKey = "pi/key"),
            ModelUi("pi-b", "Pi B", isLocalProjectSupported = true, connectionKey = "pi/key"),
            ModelUi("pi-c", "Pi C", isLocalProjectSupported = true, connectionKey = "pi/other"),
            ModelUi("codex", "Codex", isLocalProjectSupported = true),
        )
        val restored = state.copy(models = models, sessions = persistentListOf(session.copy(modelId = "pi-a")))
        assertEquals(listOf("pi-a", "pi-b"), restored.paneContent(restored.panes.first()).models.map { it.id })
    }

    @Test
    fun `pane content resolves the session project and the rename of its header only`() {
        val renaming = state.copy(sidebar = SidebarUi(renaming = RenameUi("s", "New", paneOrigin(0))))
        val content = renaming.paneContent(renaming.panes.first())
        assertEquals(site, content.project)
        assertTrue(content.isFocused)
        assertEquals("New", content.renaming?.title)
        assertNull(renaming.paneContent(renaming.panes[1]).renaming)
    }

    @Test
    fun `new sessions use the project of the focused pane then of its session then the first one`() {
        assertEquals("s", state.sidebarInput().selectedId)
        assertEquals("q", state.sidebarInput().newSessionProjectId)
        val page = state.copy(panes = persistentListOf(PaneUi(0, projectId = "p")))
        assertEquals("p", page.sidebarInput().newSessionProjectId)
        val general = state.copy(panes = persistentListOf(PaneUi(0)))
        assertEquals("p", general.sidebarInput().newSessionProjectId)
        assertNull(general.copy(projects = persistentListOf()).sidebarInput().newSessionProjectId)
    }
}
