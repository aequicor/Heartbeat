package io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation

import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ConnectionCost
import io.aequicor.heartbeat.feature.adaptivesubagents.api.CostMode
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesState
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPreferences
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPriority
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

private typealias ScreenProvider = Provider<SubagentsScreenState, SubagentsScreenIntent, SubagentsScreenAction>

class SubagentsModelTest {
    private val haiku = target("claude", "api", "claude-haiku-4-5")
    private val directory = Directory(
        listOf(DirectoryModel(haiku, "Haiku", "Claude", "Claude · Work", emptyList(), null)),
        listOf(DirectoryConnection(EngineBindingId("api"), "Claude · Work")),
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
        assertEquals(listOf("Haiku · Claude · Work"), state.models.map { it.title })
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

    private inner class Fixture(private val scope: TestScope, initial: ModelProfilesState) {
        val machine = LibrarySpecMachine(initial)
        private val model = SubagentsModel(
            machine,
            flowOf(directory),
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
