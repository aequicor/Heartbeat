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
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarness
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnessChoices
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnesses
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfaceCardFocus
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfacePlace
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfacePrompt
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfaces
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioToolRun
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
    fun `panes are bound with their chat and project and the harnesses chosen for a new chat`() = runTest {
        val workspace = StudioWorkspace(
            emptyList(),
            listOf(StudioSession("s-chat", "p-app", "Chat", TestClock(this).now(), nativeSession = native)),
        )
        val repository = object : StudioRepository by InMemoryStudioRepository(TestClock(this)) {
            override fun observeWorkspace() = flowOf(workspace)
        }
        val surfaces = FakeStudioSurfaces()
        val state = ready(StudioPane(0, sessionId = "s-chat"), StudioPane(1, projectId = "p-new"))
        val harnesses = object : StudioHarnesses by StudioHarnesses.None {
            override val choices = flowOf(
                StudioHarnessChoices(listOf(StudioHarness("pr_map", "PR map", projectIds = setOf("p-new")))),
            )
        }
        val fixture = Fixture(this, state, repository, harnesses = harnesses, surfaces = surfaces)
        val screen = fixture.subscribe()
        assertEquals(listOf("pr_map"), screen.states.value.harnessChoices.options.map { it.id })
        fixture.model.store.intent(AiStudioScreenIntent.SelectHarness(1, "pr_map", true))
        runCurrent()
        assertEquals<Set<String>?>(setOf("pr_map"), screen.states.value.harnessChoices.panes[1])

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
    fun `a delayed prompt cannot fill a pane now showing another project`() = runTest {
        val surfaces = FakeStudioSurfaces()
        val fixture = Fixture(this, ready(StudioPane(0, projectId = "p-new")), surfaces = surfaces)
        val screen = fixture.subscribe()
        surfaces.prompts.emit(
            StudioSurfacePrompt(0, "Old project", true, StudioSurfacePlace(workspace = WorkspaceRef("p-old"))),
        )
        runCurrent()
        assertTrue(screen.states.value.drafts[screen.states.value.draftKey(0)].isNullOrEmpty())
        assertTrue(fixture.machine.sent.none { it is AiStudioIntent.Public.Submit })

        surfaces.prompts.emit(
            StudioSurfacePrompt(0, "Current project", false, StudioSurfacePlace(workspace = WorkspaceRef("p-new"))),
        )
        runCurrent()
        assertEquals("Current project", screen.states.value.drafts[screen.states.value.draftKey(0)])
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

    @Test
    fun `card output reaches the store and only its current acknowledgement clears it`() = runTest {
        val clock = TestClock(this)
        val workspace = StudioWorkspace(
            emptyList(),
            listOf(StudioSession("chat", null, "Chat", clock.now(), nativeSession = native)),
        )
        val repository = object : StudioRepository by InMemoryStudioRepository(clock) {
            override fun observeWorkspace() = flowOf(workspace)

            override fun observeMessages(sessionId: String) = flowOf(
                listOf(
                    StudioMessage.Reply(
                        "reply",
                        clock.now(),
                        tools = listOf(StudioToolRun("tool", "UI", shownCardId = "card")),
                    ),
                ),
            )
        }
        val surfaces = FakeStudioSurfaces()
        val fixture = Fixture(this, ready(StudioPane(0, sessionId = "chat")), repository, surfaces = surfaces)
        val screen = fixture.subscribe()
        surfaces.cardFocus.emit(StudioSurfaceCardFocus(0, "card", StudioSurfacePlace(chat = native)))
        runCurrent()
        val first = screen.states.value.cardFocusRequests.getValue(0)
        surfaces.cardFocus.emit(StudioSurfaceCardFocus(0, "card", StudioSurfacePlace(chat = native)))
        runCurrent()
        val second = screen.states.value.cardFocusRequests.getValue(0)
        assertTrue(second.sequence > first.sequence)
        screen.intent(AiStudioScreenIntent.CardFocusApplied(0, first.sequence))
        runCurrent()
        assertEquals(second, screen.states.value.cardFocusRequests[0])
        screen.intent(AiStudioScreenIntent.CardFocusApplied(0, second.sequence))
        runCurrent()
        assertTrue(screen.states.value.cardFocusRequests.isEmpty())
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
        override val cardFocus = MutableSharedFlow<StudioSurfaceCardFocus>(extraBufferCapacity = 4)

        override fun host(paneId: Int): String = "test.pane-$paneId"
        override val dock: String = "test.side"
        override val panel: Flow<Long?> = flowOf(null)

        override suspend fun closePanel() = Unit

        override suspend fun keep(places: Flow<Map<Int, StudioSurfacePlace>>) = places.collect { this.places += it }
    }
}
