package io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation

import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveModels
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ConnectionCost
import io.aequicor.heartbeat.feature.adaptivesubagents.api.CostMode
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelChoice
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelKey
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfile
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesIntent
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesState
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ProfileSource
import io.aequicor.heartbeat.feature.adaptivesubagents.api.RatingSource
import io.aequicor.heartbeat.feature.adaptivesubagents.api.Ratings
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchFailure
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchJob
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchStage
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchSubject
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SelectionContext
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SelectionOption
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPreferences
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPriority
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentRequest
import io.aequicor.heartbeat.feature.adaptivesubagents.api.Traits
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.LibrarySpecMachine
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.TestDispatchers
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.Directory
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.DirectoryConnection
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.DirectoryModel
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.target
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import pro.respawn.flowmvi.api.Provider
import pro.respawn.flowmvi.dsl.collect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private typealias ScreenProvider = Provider<SubagentsScreenState, SubagentsScreenIntent, SubagentsScreenAction>

class SubagentsModelTest {
    private val haiku = target("claude", "api", "claude-haiku-4-5")
    private val opus = target("claude", "api", "claude-opus-4-1")
    private val directory = Directory(
        listOf(
            DirectoryModel(haiku, "Haiku", "Claude", "Claude · Work", emptyList(), null),
            DirectoryModel(opus, "Opus", "Claude", "Claude · Work", listOf("low", "high"), "high"),
        ),
        listOf(DirectoryConnection(EngineBindingId("api"), "Claude · Work")),
        defaultTarget = opus,
    )
    private val described = ModelProfile(
        ModelKey.of(opus),
        "Opus",
        base = Traits(Ratings(5, 2, 2)),
        updatedAt = Instant.fromEpochSeconds(1),
    )

    @Test
    fun `the screen mirrors preferences models and connection billing`() = runTest {
        val preferences = SubagentPreferences(
            SubagentPriority.Cost,
            routerTarget = haiku,
            connectionCosts = listOf(ConnectionCost(EngineBindingId("api"), CostMode.Subscription)),
        )
        val screen = Fixture(this, ModelProfilesState.Ready(preferences = preferences)).subscribe()
        val state = screen.states.value
        assertEquals(PriorityUi.Cost, state.priority)
        assertEquals(haiku.uiId(), state.routerModel)
        assertNull(state.researcherModel)
        assertEquals(listOf("Haiku · Claude · Work", "Opus · Claude · Work"), state.models.map { it.title })
        assertEquals(listOf(ConnectionUi("api", "Claude · Work", CostModeUi.Subscription)), state.connections)
    }

    @Test
    fun `changes go to the library and come back`() = runTest {
        val fixture = Fixture(this, ModelProfilesState.Ready())
        val screen = fixture.subscribe()
        screen.intent(SubagentsScreenIntent.SelectPriority(PriorityUi.Quality))
        screen.intent(SubagentsScreenIntent.SetCrossEngine(false))
        screen.intent(SubagentsScreenIntent.SelectResearcherModel(haiku.uiId()))
        screen.intent(SubagentsScreenIntent.SelectCost("api", CostModeUi.Local))
        runCurrent()
        val stored = (fixture.machine.state.value as ModelProfilesState.Ready).preferences
        assertEquals(SubagentPriority.Quality, stored.defaultPriority)
        assertFalse(stored.isCrossEngineAllowed)
        assertEquals(haiku, stored.researcherTarget)
        assertEquals(CostMode.Local, stored.costOf(EngineBindingId("api")))
        assertEquals(PriorityUi.Quality, screen.states.value.priority)
        screen.intent(SubagentsScreenIntent.SelectResearcherModel(null))
        runCurrent()
        assertNull((fixture.machine.state.value as ModelProfilesState.Ready).preferences.researcherTarget)
    }

    @Test
    fun `a failed library shows a retry that reloads it`() = runTest {
        val fixture = Fixture(this, ModelProfilesState.Failed)
        val screen = fixture.subscribe()
        assertEquals(SubagentsErrorUi.LoadFailed, screen.states.value.error)
        screen.intent(SubagentsScreenIntent.Reload)
        runCurrent()
        assertEquals(ModelProfilesState.Loading, fixture.machine.state.value)
        assertFalse(screen.states.value.isLoaded)
    }

    @Test
    fun `models show their description source ratings and running research`() = runTest {
        val job = ResearchJob("j", ResearchSubject(ModelKey.of(haiku), "Haiku"), opus, ResearchStage.Reading, 2)
        val library = ModelProfilesState.Ready(
            listOf(described.copy(source = ProfileSource.Research)),
            research = listOf(job),
        )
        val entries = Fixture(this, library).subscribe().states.value.entries
        assertEquals(listOf("Haiku", "Opus"), entries.map { it.title })
        assertEquals(StageUi.Reading to 2, entries[0].stage to entries[0].sources)
        assertEquals(DescriptionUi.Estimated, entries[0].description)
        assertEquals(DescriptionUi.Researched to "5 · 2 · 2", entries[1].description to entries[1].ratings)
    }

    @Test
    fun `research starts on the default model and an edited description asks first`() = runTest {
        val fixture = Fixture(this, ModelProfilesState.Ready(listOf(described)), isResearchEnabled = true)
        val screen = fixture.subscribe()
        assertTrue(screen.states.value.isResearchEnabled && screen.states.value.hasResearcher)
        screen.intent(SubagentsScreenIntent.Research(ModelKey.of(haiku).uiId()))
        runCurrent()
        val started = (fixture.machine.state.value as ModelProfilesState.Ready).research.single()
        assertEquals(ResearchSubject(ModelKey.of(haiku), "Haiku"), started.subject)
        assertEquals(opus, started.researcher)
        val opusId = ModelKey.of(opus).uiId()
        screen.intent(SubagentsScreenIntent.Research(opusId))
        runCurrent()
        assertEquals(opusId, screen.states.value.confirming)
        screen.intent(SubagentsScreenIntent.ConfirmResearch)
        runCurrent()
        val running = (fixture.machine.state.value as ModelProfilesState.Ready).research
        assertEquals(listOf("low", "high"), running.last().subject.efforts)
        assertNull(screen.states.value.confirming)
        screen.intent(SubagentsScreenIntent.CancelResearch(opusId))
        runCurrent()
        assertEquals(1, (fixture.machine.state.value as ModelProfilesState.Ready).research.size)
    }

    @Test
    fun `a failed research shows a banner and the trial lists the shortlist`() = runTest {
        val option = SelectionOption(haiku, null, "Haiku", Ratings(3, 5, 5), RatingSource.Heuristic, 3.456)
        val fixture = Fixture(this, ModelProfilesState.Ready(), options = listOf(option))
        val screen = fixture.subscribe()
        fixture.machine.send(ModelProfilesIntent.Public.Research("j", ResearchSubject(ModelKey.of(haiku), "H"), opus))
        fixture.machine.send(ModelProfilesIntent.Internal.ResearchFailed("j", ResearchFailure.NoSources))
        runCurrent()
        assertEquals(ResearchFailureUi.NoSources, screen.states.value.researchFailure)
        screen.intent(SubagentsScreenIntent.ChangeTrialTask("Fix typos"))
        screen.intent(SubagentsScreenIntent.SelectTrialPriority(PriorityUi.Speed))
        screen.intent(SubagentsScreenIntent.RunTrial)
        runCurrent()
        val trial = screen.states.value.trial
        assertEquals(listOf(TrialOptionUi("Haiku", null, "3 · 5 · 5", "3.46", DescriptionUi.Estimated)), trial.options)
        assertEquals(SubagentRequest("Fix typos", SubagentPriority.Speed), fixture.requests.single())
    }

    private inner class Fixture(
        private val scope: TestScope,
        initial: ModelProfilesState,
        isResearchEnabled: Boolean = false,
        options: List<SelectionOption> = emptyList(),
    ) {
        val machine = LibrarySpecMachine(initial)
        val requests = mutableListOf<SubagentRequest>()
        private val adaptive = object : AdaptiveModels {
            override suspend fun select(request: SubagentRequest, context: SelectionContext): ModelChoice =
                error("unused")

            override suspend fun preview(request: SubagentRequest, context: SelectionContext): List<SelectionOption> {
                requests += request
                return options
            }
        }
        private val model = SubagentsModel(
            machine,
            flowOf(directory),
            adaptive,
            flowOf(isResearchEnabled),
            HeartbeatStoreFactory(TestDispatchers(StandardTestDispatcher(scope.testScheduler))),
            scope.backgroundScope,
        )

        suspend fun subscribe(): ScreenProvider {
            val provider = CompletableDeferred<ScreenProvider>()
            scope.backgroundScope.launch {
                model.store.collect {
                    provider.complete(this)
                    awaitCancellation()
                }
            }
            scope.runCurrent()
            return provider.await()
        }
    }
}
