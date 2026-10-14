package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfaceApp
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfacePlace
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote.isAllowedRemotely
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.HarnessChoicesUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioAppsTest {
    private val native = SessionRef(EngineId("engine"), SessionSourceId("source"), "chat")
    private val workspace = StudioWorkspace(
        emptyList(),
        listOf(StudioSession("chat", "project", "Chat", Instant.DISTANT_PAST, nativeSession = native)),
    )
    private val app = StudioSurfaceApp(
        "harness:gym:app",
        "Gym",
        "gym",
        StudioSurfacePlace(native, WorkspaceRef("project")),
    )

    @Test
    fun `native app metadata only follows the exact pane context and owner selection`() {
        val state = AiStudioScreenState(panes = persistentListOf(PaneUi(0, sessionId = "chat")))
        assertEquals("Gym", state.withApps(mapOf(0 to app), workspace).apps[0]?.title)
        assertTrue(state.withApps(mapOf(1 to app), workspace).apps.isEmpty())
        val old = app.copy(place = app.place.copy(chat = native.copy(nativeId = "old")))
        assertTrue(state.withApps(mapOf(0 to old), workspace).apps.isEmpty())
        assertTrue(state.withApps(mapOf(0 to app), null).apps.isEmpty())

        val start = state.copy(
            panes = persistentListOf(PaneUi(0, projectId = "project")),
            harnessChoices = HarnessChoicesUi(panes = persistentMapOf(0 to persistentSetOf("gym"))),
        )
        val selected = app.copy(
            place = StudioSurfacePlace(workspace = WorkspaceRef("project"), harnesses = setOf("gym")),
        )
        assertEquals<Set<String>?>(
            setOf("gym"),
            start.withApps(mapOf(0 to selected), workspace).apps[0]?.selectedHarnesses,
        )
        val stale = start.copy(harnessChoices = HarnessChoicesUi())
        assertTrue(stale.withApps(mapOf(0 to selected), workspace).apps.isEmpty())
    }

    @Test
    fun `replacing a pane removes its app until the new context resolves and remote hosts cannot open it`() {
        val machine = AiStudioState.Ready(
            panes = listOf(StudioPane(0, sessionId = "chat")),
            focusedPaneId = 0,
            settings = DefaultRunSettings,
            defaultProjectId = "project",
        )
        val shown = AiStudioScreenState().reflectMachine(machine).withApps(mapOf(0 to app), workspace)
        assertEquals(1, shown.apps.size)
        val switched = shown.reflectMachine(machine.copy(panes = listOf(StudioPane(0, sessionId = "other"))))
        assertTrue(switched.apps.isEmpty())
        assertTrue(switched.reflectMachine(machine).apps.isEmpty())
        assertTrue(!isAllowedRemotely(AiStudioScreenIntent.OpenApp(0, app.surfaceId)))
    }
}
