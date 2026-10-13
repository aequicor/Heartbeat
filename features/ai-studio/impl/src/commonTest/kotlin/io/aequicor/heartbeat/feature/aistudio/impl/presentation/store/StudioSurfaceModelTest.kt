package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.impl.data.InMemoryStudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfacePlace
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfacePrompt
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfaces
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import io.aequicor.heartbeat.feature.aistudio.impl.domain.TestClock
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModelTest.Fixture
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudioSurfaceModelTest {
    private val native = SessionRef(EngineId("engine"), SessionSourceId("source"), "chat")

    @Test
    fun `panes are bound with their chat, project and the harnesses chosen for a new chat`() = runTest {
        val workspace = StudioWorkspace(
            emptyList(),
            listOf(StudioSession("s-chat", "p-app", "Chat", TestClock(this).now(), nativeSession = native)),
        )
        val repository = object : StudioRepository by InMemoryStudioRepository(TestClock(this)) {
            override fun observeWorkspace() = flowOf(workspace)
        }
        val surfaces = FakeStudioSurfaces()
        val state = ready(StudioPane(0, sessionId = "s-chat"), StudioPane(1, projectId = "p-new"))
        val fixture = Fixture(this, state, repository, surfaces = surfaces)
        fixture.subscribe()
        fixture.model.store.intent(AiStudioScreenIntent.SelectHarness(1, "pr_map", true))
        runCurrent()

        assertEquals(
            mapOf(
                0 to StudioSurfacePlace(chat = native, workspace = WorkspaceRef("p-app")),
                1 to StudioSurfacePlace(workspace = WorkspaceRef("p-new"), harnesses = setOf("pr_map")),
            ),
            surfaces.places.last(),
        )
    }

    @Test
    fun `a surface prompt fills the composer of its pane and is sent when asked`() = runTest {
        val surfaces = FakeStudioSurfaces()
        val fixture = Fixture(this, ready(StudioPane(0), StudioPane(1)), surfaces = surfaces)
        val screen = fixture.subscribe()

        surfaces.prompts.emit(StudioSurfacePrompt(0, "Rank the PRs", isSubmitted = false))
        surfaces.prompts.emit(StudioSurfacePrompt(7, "Elsewhere", isSubmitted = true))
        runCurrent()
        assertEquals("Rank the PRs", screen.states.value.drafts[screen.states.value.draftKey(0)])
        assertTrue(fixture.machine.sent.none { it is AiStudioIntent.Public.Submit })

        surfaces.prompts.emit(StudioSurfacePrompt(1, "Map the PRs", isSubmitted = true))
        runCurrent()
        val submit = fixture.machine.sent.filterIsInstance<AiStudioIntent.Public.Submit>().single()
        assertEquals(1 to "Map the PRs", submit.paneId to submit.prompt)
    }

    @Test
    fun `a prompt joins a started draft and never sends the user's text or a host command`() = runTest {
        val surfaces = FakeStudioSurfaces()
        val fixture = Fixture(this, ready(StudioPane(0), StudioPane(1)), surfaces = surfaces)
        val screen = fixture.subscribe()
        fixture.model.store.intent(AiStudioScreenIntent.Suggestions.DraftChanged(0, "My notes"))
        runCurrent()

        surfaces.prompts.emit(StudioSurfacePrompt(0, "Rank the PRs", isSubmitted = true))
        surfaces.prompts.emit(StudioSurfacePrompt(1, "/compact", isSubmitted = true))
        runCurrent()
        assertEquals("My notes\n\nRank the PRs", screen.states.value.drafts[screen.states.value.draftKey(0)])
        assertEquals("/compact", screen.states.value.drafts[screen.states.value.draftKey(1)])
        assertTrue(fixture.machine.sent.none { it is AiStudioIntent.Public.Submit })
    }

    private fun ready(vararg panes: StudioPane) = AiStudioState.Ready(
        panes = panes.toList(),
        focusedPaneId = panes.first().id,
        settings = DefaultRunSettings,
        defaultProjectId = "p-heartbeat",
    )

    private class FakeStudioSurfaces : StudioSurfaces {
        val places = mutableListOf<Map<Int, StudioSurfacePlace>>()
        override val availability: Flow<Boolean> = flowOf(true)
        override val prompts = MutableSharedFlow<StudioSurfacePrompt>(extraBufferCapacity = 4)

        override fun host(paneId: Int): String = "test.pane-$paneId"
        override val dock: String = "test.side"
        override val panel: Flow<Long?> = flowOf(null)

        override suspend fun closePanel() = Unit

        override suspend fun keep(places: Flow<Map<Int, StudioSurfacePlace>>) = places.collect { this.places += it }
    }
}
