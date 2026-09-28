package io.aequicor.heartbeat.platform.dibundle

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.navigation.RootHost
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioRoute
import io.aequicor.heartbeat.feature.settings.api.SettingsRoute
import io.aequicor.heartbeat.feature.settings.api.SettingsSection
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelMachineKey
import io.aequicor.heartbeat.platform.dibundle.root.HeartbeatRoot
import io.aequicor.heartbeat.platform.dibundle.root.RootChild
import io.aequicor.heartbeat.platform.dibundle.root.RootStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import io.aequicor.heartbeat.feature.welcome.api.WelcomeRoute as ProductionWelcomeRoute

/** The settings window through the real graph: deep links, the embedded section and closing with back. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsIntegrationTest {
    private val processes = mutableListOf<Process>()

    private inner class Process {
        val graph = createGraphFactory<TestAppGraph.Factory>().create(PersistedProfile())
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
        assertNotNull(process.graph.machines.find(TogglesPanelMachineKey), "the section runs inside the window")

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
        process.root.handleDeepLink("heartbeat://settings/unknown")
        advanceUntilIdle()
        assertEquals(listOf<Route>(ProductionWelcomeRoute), process.host.routes, "an unknown section is rejected")

        process.root.handleDeepLink("heartbeat://settings")
        advanceUntilIdle()
        assertEquals(listOf(ProductionWelcomeRoute, SettingsRoute()), process.host.routes)
        // Feature flags are the only section of the guest tree, so they open without a profile.
        assertNotNull(process.graph.machines.find(TogglesPanelMachineKey))
        process.close()
    }
}
