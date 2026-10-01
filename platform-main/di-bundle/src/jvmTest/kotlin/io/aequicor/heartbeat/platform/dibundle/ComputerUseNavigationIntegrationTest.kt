package io.aequicor.heartbeat.platform.dibundle

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.RootHost
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioRoute
import io.aequicor.heartbeat.feature.computeruse.api.CaptureOwner
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapabilities
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineKey
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import io.aequicor.heartbeat.feature.computeruse.api.WindowId
import io.aequicor.heartbeat.feature.computeruse.api.WindowTarget
import io.aequicor.heartbeat.feature.settings.api.SettingsRoute
import io.aequicor.heartbeat.feature.settings.api.SettingsSection
import io.aequicor.heartbeat.platform.dibundle.root.HeartbeatRoot
import io.aequicor.heartbeat.platform.dibundle.root.RootChild
import io.aequicor.heartbeat.platform.dibundle.root.RootStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import io.aequicor.heartbeat.feature.welcome.api.WelcomeRoute as ProductionWelcomeRoute

/** Real root navigation with a controlled computer-use machine; route components still come from Metro. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComputerUseNavigationIntegrationTest {
    private val processes = mutableListOf<Process>()
    private val settings = SettingsRoute(SettingsSection.FeatureFlags)
    private val owner = CaptureOwner.Agent(
        SessionRef(EngineId("pi"), SessionSourceId("local"), "chat"),
        TurnId("turn"),
    )
    private val capture = ComputerUseState.Capturing(
        CaptureSessionId("capture"),
        ComputerUseMode.Desktop(),
        owner,
        ComputerUseCapabilities(true, true, true, true),
        isOpen = false,
    )

    @Test
    fun `agent capture brings existing studio forward once and preserves settings and later navigation`() =
        runNavigationTest {
            val process = Process()
            advanceUntilIdle()
            val host = process.host
            val studio = host.stack.value.active.instance
            host.navigator.navigate(settings)
            advanceUntilIdle()
            val settingsComponent = host.stack.value.active.instance

            process.registry.computer.state.value = capture.copy(owner = CaptureOwner.Panel, isOpen = true)
            runCurrent()
            assertSame(settingsComponent, host.stack.value.active.instance)
            assertEquals(listOf<Route>(AiStudioRoute, settings), host.routes)

            process.registry.computer.state.value = capture
            runCurrent()
            assertSame(studio, host.stack.value.active.instance, "preparing capture brings the existing studio forward")
            assertSame(settingsComponent, host.stack.value.backStack.single().instance)
            assertEquals(listOf<Route>(settings, AiStudioRoute), host.routes)

            host.navigator.navigate(settings, NavOptions(launch = LaunchMode.BringToFront))
            process.registry.computer.state.value = capture.copy(isOpen = true, frameCount = 1)
            runCurrent()
            assertSame(settingsComponent, host.stack.value.active.instance, "frames do not override user navigation")
            val target = WindowTarget(WindowId("app"), "App", "Window", ScreenBounds(0, 0, 800, 600))
            val switched = capture.copy(
                session = CaptureSessionId("replacement"),
                mode = ComputerUseMode.Window(target),
            )
            process.registry.computer.state.value = switched
            runCurrent()
            process.registry.computer.state.value = switched.copy(isOpen = true)
            runCurrent()
            assertSame(settingsComponent, host.stack.value.active.instance, "same owner switching mode stays quiet")

            process.registry.computer.state.value = capture.copy(owner = owner.copy(turn = TurnId("next")))
            runCurrent()
            assertSame(studio, host.stack.value.active.instance, "a newer turn brings the studio forward again")
            assertSame(settingsComponent, host.stack.value.backStack.single().instance)

            host.navigator.navigate(settings, NavOptions(launch = LaunchMode.BringToFront))
            process.registry.computer.state.value = ComputerUseState.Idle
            runCurrent()
            assertSame(settingsComponent, host.stack.value.active.instance, "capture release preserves navigation")
            process.registry.computer.state.value = capture
            runCurrent()
            assertSame(studio, host.stack.value.active.instance, "a new contiguous capture brings the studio forward")
        }

    @Test
    fun `capture preparing before profile attachment brings studio above initial settings`() = runNavigationTest {
        val process = Process(initialCapture = capture, profileRoutes = listOf(settings))
        advanceUntilIdle()
        assertEquals(listOf<Route>(settings, AiStudioRoute), process.host.routes)
        assertIs<RootChild.Profile>(process.root.slot.value.child?.instance)
        assertEquals(true, process.root.computerUse.value.isActive)
    }

    private inner class Process(
        initialCapture: ComputerUseState = ComputerUseState.Idle,
        profileRoutes: List<Route> = listOf(AiStudioRoute),
    ) {
        private val disk = PersistedProfile()
        val graph = createGraphFactory<TestAppGraph.Factory>().create(disk)
        val registry = NavigationRegistry(graph.machines, initialCapture)
        private val lifecycle = LifecycleRegistry().apply { resume() }
        val root = HeartbeatRoot(
            DefaultComponentContext(lifecycle),
            object : HeartbeatGraph by graph {
                override val machines: MachineRegistry = registry
            },
            RootStart(listOf(ProductionWelcomeRoute), profileRoutes),
            localProfile = ProfileId("computer-navigation"),
        )
        val host: RootHost
            get() = checkNotNull(assertIs<RootChild.Profile>(root.slot.value.child?.instance).host.value)

        init {
            processes += this
        }

        suspend fun close() {
            lifecycle.destroy()
            (graph.appScope as OwnedScope).close()
            graph.appScope.coroutineScope.coroutineContext[Job]?.join()
            File(disk.storageRoot).deleteRecursively()
        }
    }

    private fun runNavigationTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            body()
        } finally {
            withContext(NonCancellable) {
                processes.asReversed().forEach { it.close() }
                processes.clear()
                advanceUntilIdle()
            }
            Dispatchers.resetMain()
        }
    }

    private val RootHost.routes: List<Route> get() = stack.value.items.map { it.configuration.route }
}

private typealias ComputerNavigationRef =
    MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>

private class NavigationRegistry(private val delegate: MachineRegistry, initial: ComputerUseState) :
    MachineRegistry by delegate {
    val computer = NavigationComputerMachine(initial)
    private val reference = MutableStateFlow<ComputerNavigationRef?>(computer)

    @Suppress("UNCHECKED_CAST")
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O>? = if (key == ComputerUseMachineKey) {
        reference.value as MachineRef<S, P, O>?
    } else {
        delegate.find(key)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> = if (key == ComputerUseMachineKey) {
        reference as StateFlow<MachineRef<S, P, O>?>
    } else {
        delegate.observe(key)
    }

    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult = if (key == ComputerUseMachineKey) {
        computer.send(intent as ComputerUseIntent)
    } else {
        delegate.send(key, intent)
    }
}

private class NavigationComputerMachine(initial: ComputerUseState) :
    Machine<ComputerUseState, ComputerUseIntent, ComputerUseOutput> {
    override val name = ComputerUseMachineKey.name
    override val state = MutableStateFlow(initial)
    override val outputs = MutableSharedFlow<ComputerUseOutput>()

    override suspend fun send(intent: ComputerUseIntent): SendResult = SendResult.Accepted
}
