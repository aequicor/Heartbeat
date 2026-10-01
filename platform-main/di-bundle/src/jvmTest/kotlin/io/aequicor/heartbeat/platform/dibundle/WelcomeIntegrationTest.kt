package io.aequicor.heartbeat.platform.dibundle

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.statekeeper.SerializableContainer
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.navigation.RootHost
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioRoute
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime
import io.aequicor.heartbeat.feature.settings.api.SettingsRoute
import io.aequicor.heartbeat.feature.settings.api.SettingsSection
import io.aequicor.heartbeat.feature.togglespanel.api.ToggleOperation
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelIntent
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelMachineKey
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelState
import io.aequicor.heartbeat.feature.welcome.api.CinematicIntro
import io.aequicor.heartbeat.feature.welcome.api.WelcomeDestination
import io.aequicor.heartbeat.feature.welcome.api.WelcomeIntent
import io.aequicor.heartbeat.feature.welcome.api.WelcomeMachineKey
import io.aequicor.heartbeat.feature.welcome.api.WelcomeState
import io.aequicor.heartbeat.platform.dibundle.root.HeartbeatRoot
import io.aequicor.heartbeat.platform.dibundle.root.RootChild
import io.aequicor.heartbeat.platform.dibundle.root.RootStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import io.aequicor.heartbeat.feature.welcome.api.WelcomeRoute as ProductionWelcomeRoute

@OptIn(ExperimentalCoroutinesApi::class)
class WelcomeIntegrationTest {
    private val processes = mutableListOf<Process>()
    private val clock = RealTestClock()

    private inner class Process(val disk: PersistedProfile, saved: SerializableContainer? = null) {
        val graph = createGraphFactory<TestAppGraph.Factory>().create(disk)
        private val keeper = StateKeeperDispatcher(saved)
        private val lifecycle = LifecycleRegistry().apply { resume() }
        val root = HeartbeatRoot(
            DefaultComponentContext(lifecycle, stateKeeper = keeper),
            graph,
            RootStart(listOf(ProductionWelcomeRoute), listOf(AiStudioRoute)),
        )
        val host: RootHost get() = when (val child = root.slot.value.child?.instance) {
            is RootChild.Guest -> child.host
            is RootChild.Profile -> checkNotNull(child.host.value)
            else -> error("root host not ready")
        }
        val welcome get() = checkNotNull(graph.machines.find(WelcomeMachineKey))
        val toggles get() = graph as TestToggleAccessors

        init {
            processes += this
        }

        fun save(): SerializableContainer = Json.decodeFromString(
            SerializableContainer.serializer(),
            Json.encodeToString(SerializableContainer.serializer(), keeper.save()),
        )

        suspend fun close() {
            lifecycle.destroy()
            (graph.appScope as OwnedScope).close()
            graph.appScope.coroutineScope.coroutineContext[Job]?.join()
        }
    }

    private val RootHost.routes: List<Route> get() = stack.value.items.map { it.configuration.route }

    @AfterTest
    fun cleanup() {
        processes.forEach { (it.graph.appScope as OwnedScope).close() }
        Dispatchers.resetMain()
        clock.close()
    }

    @Test
    fun `guest toggles return to welcome and studio opens a local profile`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val process = Process(PersistedProfile())
        advanceUntilIdle()
        process.welcome.send(WelcomeIntent.Public.Skip)
        process.welcome.send(WelcomeIntent.Public.Open(WelcomeDestination.Toggles))
        awaitStorage(this) { process.host.routes.size == 2 }
        assertEquals(listOf(ProductionWelcomeRoute, SettingsRoute(SettingsSection.FeatureFlags)), process.host.routes)
        process.host.onBack()
        advanceUntilIdle()
        assertEquals(WelcomeState.Ready, process.welcome.state.value)
        // The studio opens with one press also while the engine runtime is off (the default): no second welcome.
        assertEquals(false, process.toggles.featureToggles.get(StudioEngineRuntime))
        process.welcome.send(WelcomeIntent.Public.Open(WelcomeDestination.Studio))
        advanceUntilIdle()
        assertIs<RootChild.Profile>(process.root.slot.value.child?.instance)
        assertEquals(listOf<Route>(AiStudioRoute), process.host.routes)
        assertEquals("local", process.graph.profileSessions.active.value?.id?.value)
        process.close()
    }

    @Test
    fun `saved intro restores ready while a new session starts an intro`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val disk = PersistedProfile()
        val before = Process(disk)
        advanceUntilIdle()
        before.welcome.state.first { it == WelcomeState.Intro }
        val saved = before.save()
        before.close()
        val restored = Process(disk, saved)
        advanceUntilIdle()
        assertEquals(WelcomeState.Ready, restored.welcome.state.value)
        restored.close()
        val fresh = Process(disk)
        advanceUntilIdle()
        fresh.welcome.state.first { it == WelcomeState.Intro }
        fresh.close()
    }

    @Test
    fun `restored profile opens the studio in its profile host`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val disk = PersistedProfile()
        val before = Process(disk)
        advanceUntilIdle()
        before.welcome.send(WelcomeIntent.Public.Skip)
        before.welcome.send(WelcomeIntent.Public.Open(WelcomeDestination.Studio))
        advanceUntilIdle()
        val saved = before.save()
        before.close()
        val restored = Process(disk, saved)
        advanceUntilIdle()
        assertIs<RootChild.Profile>(restored.root.slot.value.child?.instance)
        assertEquals(listOf<Route>(AiStudioRoute), restored.host.routes)
        restored.close()
    }

    @Test
    fun `panel writes survive process restart and affect the next intro`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val disk = PersistedProfile()
        val before = Process(disk)
        advanceUntilIdle()
        before.welcome.send(WelcomeIntent.Public.Skip)
        before.welcome.send(WelcomeIntent.Public.Open(WelcomeDestination.Toggles))
        awaitStorage(this) { before.graph.machines.find(TogglesPanelMachineKey) != null }
        val panel = checkNotNull(before.graph.machines.find(TogglesPanelMachineKey))
        panel.state.first { it is TogglesPanelState.Active && it.rows != null }
        panel.send(TogglesPanelIntent.Public.Apply(ToggleOperation.SetFlag(CinematicIntro, false)))
        panel.state.first {
            it is TogglesPanelState.Active && it.pending == null &&
                it.rows?.any { row -> row.toggle == CinematicIntro && row.value == false && row.isOverridden } == true
        }
        panel.send(TogglesPanelIntent.Public.Apply(ToggleOperation.SetChoice(TestToggles.Mode, "Deep")))
        panel.state.first {
            it is TogglesPanelState.Active && it.pending == null &&
                it.rows?.any { row -> row.toggle == TestToggles.Mode && row.value == "Deep" } == true
        }
        before.close()
        val after = Process(disk)
        advanceUntilIdle()
        after.welcome.state.first { it == WelcomeState.Ready }
        assertEquals(false, after.toggles.featureToggles.get(CinematicIntro))
        assertEquals("Deep", after.toggles.featureToggles.get(TestToggles.Mode))
        after.toggles.toggleControl.reset(CinematicIntro)
        assertTrue(after.toggles.featureToggles.get(CinematicIntro))
        after.toggles.toggleControl.resetAll()
        assertEquals("Fast", after.toggles.featureToggles.get(TestToggles.Mode))
        assertTrue(after.toggles.toggleControl.observeStates().first().none { it.isOverridden })
        after.close()
    }

    /** Opening the flags reads the unified settings toggle from storage off the test dispatcher. */
    private suspend fun awaitStorage(test: TestScope, isDone: () -> Boolean) {
        repeat(STORAGE_ATTEMPTS) {
            test.advanceUntilIdle()
            if (isDone()) return
            withContext(clock.dispatcher) { delay(STORAGE_POLL_MILLIS) }
        }
        test.advanceUntilIdle()
    }

    private companion object {
        const val STORAGE_ATTEMPTS = 100
        const val STORAGE_POLL_MILLIS = 20L
    }
}
