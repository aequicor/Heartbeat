package io.aequicor.heartbeat.platform.dibundle

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.navigation.RootHost
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioRoute
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityLevel
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityMachineKey
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityState
import io.aequicor.heartbeat.feature.applogs.api.AppLogsEnabled
import io.aequicor.heartbeat.feature.applogs.api.AppLogsMachineKey
import io.aequicor.heartbeat.feature.settings.api.SettingsRoute
import io.aequicor.heartbeat.feature.settings.api.SettingsSection
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelMachineKey
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelRoute
import io.aequicor.heartbeat.platform.dibundle.root.HeartbeatRoot
import io.aequicor.heartbeat.platform.dibundle.root.RootChild
import io.aequicor.heartbeat.platform.dibundle.root.RootStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.aequicor.heartbeat.feature.welcome.api.WelcomeRoute as ProductionWelcomeRoute

/** The settings window through the real graph: deep links, the embedded section and closing with back. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsIntegrationTest {
    private val processes = mutableListOf<Process>()
    private val clock = RealTestClock()

    private inner class Process(val disk: PersistedProfile = PersistedProfile()) {
        val graph = createGraphFactory<TestAppGraph.Factory>().create(disk)
        private val lifecycle = LifecycleRegistry().apply { resume() }
        val root = HeartbeatRoot(
            DefaultComponentContext(lifecycle),
            graph,
            RootStart(listOf(ProductionWelcomeRoute), listOf(AiStudioRoute)),
        )
        val host: RootHost get() = when (val child = root.slot.value.child?.instance) {
            is RootChild.Guest -> child.host
            is RootChild.Profile -> checkNotNull(child.host.value)
            else -> error("root host not ready")
        }

        init {
            processes += this
        }

        suspend fun awaitProfileHost(id: ProfileId? = null) {
            val ready = CompletableDeferred<RootChild.Profile>()
            val observation = root.slot.subscribe { slot ->
                (slot.child?.instance as? RootChild.Profile)?.takeIf { id == null || it.id == id }
                    ?.let { ready.complete(it) }
            }
            try {
                ready.await().host.filterNotNull().first()
            } finally {
                observation.cancel()
            }
        }

        suspend fun close() {
            lifecycle.destroy()
            (graph.appScope as OwnedScope).close()
            graph.appScope.coroutineScope.coroutineContext[Job]?.join()
            graph.storageMaintenance.awaitClosed()
        }
    }

    private val RootHost.routes: List<Route> get() = stack.value.items.map { it.configuration.route }

    @AfterTest
    fun cleanup() = runTest {
        processes.forEach { it.graph.closeAndAwaitStorages() }
        processes.map { it.disk.storageRoot }.distinct().forEach { check(File(it).deleteRecursively()) }
        Dispatchers.resetMain()
        clock.close()
    }

    @Test
    fun `section deep link opens the window with the embedded section and back closes it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val process = Process()
        advanceUntilIdle()
        assertNull(process.graph.machines.find(TogglesPanelMachineKey), "no flag panel before settings")

        process.root.handleDeepLink("heartbeat://settings/feature_flags")
        advanceUntilIdle()
        assertEquals(
            listOf(ProductionWelcomeRoute, SettingsRoute(SettingsSection.FeatureFlags)),
            process.host.routes,
        )
        // Sections wait for DataStore reads on IO; advanceUntilIdle only drains the test dispatcher.
        val section = process.graph.machines.observe(TogglesPanelMachineKey).filterNotNull().first()
        assertNotNull(section, "the section runs inside the window")

        process.host.onBack()
        advanceUntilIdle()
        assertEquals(listOf<Route>(ProductionWelcomeRoute), process.host.routes)
        process.close()
    }

    @Test
    fun `plain settings link and unknown sections`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val process = Process()
        advanceUntilIdle()
        (process.graph as TestToggleAccessors).toggleControl.setOverride(AppLogsEnabled, false)
        process.root.handleDeepLink("heartbeat://settings/unknown")
        advanceUntilIdle()
        assertEquals(listOf<Route>(ProductionWelcomeRoute), process.host.routes, "an unknown section is rejected")

        process.root.handleDeepLink("heartbeat://settings")
        advanceUntilIdle()
        assertEquals(listOf(ProductionWelcomeRoute, SettingsRoute()), process.host.routes)
        // With diagnostics disabled the guest flags section still waits for DataStore reads on IO.
        val section = process.graph.machines.observe(TogglesPanelMachineKey).filterNotNull().first()
        assertNotNull(section)
        process.close()
    }

    @Test
    fun `application logs open inside guest settings and close with the window`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val process = Process()
        val toggles = process.graph as TestToggleAccessors
        toggles.toggleControl.setOverride(AppLogsEnabled, true)
        advanceUntilIdle()
        assertNull(process.graph.machines.find(AppLogsMachineKey))

        process.root.handleDeepLink("heartbeat://settings/logs")
        // Settings waits for persisted flags on the IO dispatcher before exposing the logs section.
        process.graph.machines.observe(AppLogsMachineKey).filterNotNull().first()
        advanceUntilIdle()
        assertEquals(
            listOf(ProductionWelcomeRoute, SettingsRoute(SettingsSection.Logs)),
            process.host.routes,
        )
        assertNotNull(process.graph.machines.find(AppLogsMachineKey))

        process.host.onBack()
        advanceUntilIdle()
        assertEquals(listOf<Route>(ProductionWelcomeRoute), process.host.routes)
        assertNull(process.graph.machines.find(AppLogsMachineKey))
        process.close()
    }

    @Test
    fun `security deep link opens a profile setting that survives closing its window`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val process = Process()
        (process.graph as TestToggleAccessors).toggleControl.setOverride(StudioEngineRuntime, true)
        val services = process.graph.profileSessions.open(ProfileId("settings-security")).graph as AiEngineTestAccessors
        advanceUntilIdle()
        process.root.handleDeepLink("heartbeat://settings/security")
        val security = process.graph.machines.observe(StudioSecurityMachineKey).filterNotNull().first()
        security.state.first { it is StudioSecurityState.Ready }
        advanceUntilIdle()
        assertEquals(SettingsSection.Security, (process.host.routes.last() as SettingsRoute).section)
        security.send(StudioSecurityIntent.Public.SetLevel(StudioSecurityLevel.Ask))
        security.state.first { it == StudioSecurityState.Ready(StudioSecurityLevel.Ask) }
        services.agentSecuritySettings.save(StudioSecurityLevel.Trust)
        security.state.first { it == StudioSecurityState.Ready(StudioSecurityLevel.Trust) }
        // The formerly selected Ask must be actionable after an external write.
        security.send(StudioSecurityIntent.Public.SetLevel(StudioSecurityLevel.Ask))
        security.state.first { it == StudioSecurityState.Ready(StudioSecurityLevel.Ask) }
        assertEquals(StudioSecurityLevel.Ask, services.agentSecuritySettings.load())
        process.host.onBack()
        advanceUntilIdle()
        services.agentSecuritySettings.save(StudioSecurityLevel.Automatic)
        security.state.first { it == StudioSecurityState.Ready(StudioSecurityLevel.Automatic) }
        assertNotNull(process.graph.machines.find(StudioSecurityMachineKey))
        process.root.handleDeepLink("heartbeat://settings/security")
        advanceUntilIdle()
        assertEquals(StudioSecurityState.Ready(StudioSecurityLevel.Automatic), security.state.value)
        process.graph.profileSessions.open(ProfileId("settings-security-other"))
        process.awaitProfileHost(ProfileId("settings-security-other"))
        process.root.handleDeepLink("heartbeat://settings/security")
        val other = process.graph.machines.observe(StudioSecurityMachineKey).filterNotNull().first { it !== security }
        other.state.first { it is StudioSecurityState.Ready }
        other.send(StudioSecurityIntent.Public.SetLevel(StudioSecurityLevel.Trust))
        other.state.first { it == StudioSecurityState.Ready(StudioSecurityLevel.Trust) }
        assertEquals(StudioSecurityState.Ready(StudioSecurityLevel.Automatic), security.state.value)
        process.close()
    }

    @Test
    fun `corrupted security preferences retain manual confirmation after process restart`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val disk = PersistedProfile()
        val first = Process(disk)
        (first.graph as TestToggleAccessors).toggleControl.setOverride(StudioEngineRuntime, true)
        first.graph.profileSessions.open(ProfileId("corrupted-security"))
        first.awaitProfileHost()
        first.root.handleDeepLink("heartbeat://settings/security")
        val initial = first.graph.machines.observe(StudioSecurityMachineKey).filterNotNull().first()
        initial.state.first { it is StudioSecurityState.Ready }
        initial.send(StudioSecurityIntent.Public.SetLevel(StudioSecurityLevel.Ask))
        initial.state.first { it == StudioSecurityState.Ready(StudioSecurityLevel.Ask) }
        first.close()

        val file = File(disk.storageRoot).walkTopDown().single { it.name == "ai_studio_security.preferences_pb" }
        val corrupted = byteArrayOf(0x0A, 0x7F)
        file.writeBytes(corrupted)

        val restored = Process(disk)
        restored.awaitProfileHost()
        restored.root.handleDeepLink("heartbeat://settings/security")
        val security = restored.graph.machines.observe(StudioSecurityMachineKey).filterNotNull().first()
        assertEquals(
            StudioSecurityState.LoadError,
            security.state.first { it is StudioSecurityState.Ready || it == StudioSecurityState.LoadError },
        )
        security.send(StudioSecurityIntent.Public.SetLevel(StudioSecurityLevel.Trust))
        assertEquals(StudioSecurityState.LoadError, security.state.value)
        security.send(StudioSecurityIntent.Public.Retry)
        assertEquals(
            StudioSecurityState.LoadError,
            security.state.first { it is StudioSecurityState.Ready || it == StudioSecurityState.LoadError },
        )
        assertTrue(file.exists())
        assertContentEquals(corrupted, file.readBytes(), "corruption must not be rewritten as a fresh profile")
        restored.close()
    }

    @Test
    fun `the legacy flag panel route opens the settings section`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val process = Process()
        advanceUntilIdle()
        process.host.navigator.navigate(TogglesPanelRoute())
        // The redirect reads the toggle from storage off the test dispatcher.
        repeat(REDIRECT_ATTEMPTS) {
            advanceUntilIdle()
            if (process.host.routes.last() is SettingsRoute) return@repeat
            withContext(clock.dispatcher) { delay(REDIRECT_POLL_MILLIS) }
        }
        assertEquals(
            listOf(ProductionWelcomeRoute, SettingsRoute(SettingsSection.FeatureFlags)),
            process.host.routes,
        )
        process.close()
    }

    private companion object {
        const val REDIRECT_ATTEMPTS = 100
        const val REDIRECT_POLL_MILLIS = 20L
    }
}
