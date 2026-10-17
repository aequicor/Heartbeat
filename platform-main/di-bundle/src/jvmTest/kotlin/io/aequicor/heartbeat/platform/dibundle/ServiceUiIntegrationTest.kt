package io.aequicor.heartbeat.platform.dibundle

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aistudio.impl.di.AiStudioGraph
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioScreenHost
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfaces
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiCardRoute
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiEnabled
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiLibraryRoute
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiMachineKey
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiPanelRoute
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiSlotRoute
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiSurfaceRoute
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiDockId
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostId
import io.aequicor.heartbeat.feature.serviceasui.api.UiLibrary
import io.aequicor.heartbeat.feature.serviceasui.api.UiSlot
import io.aequicor.heartbeat.feature.serviceasui.api.spi.ServiceUiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ServiceUiIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val toggles = app as TestToggleAccessors

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = runTest {
        app.closeAndAwaitStorages()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `remote studio graph never owns native surface registrations`() = runTest {
        toggles.toggleControl.setOverride(ServiceUiEnabled, true)
        val profile = app.profileSessions.open(ProfileId("remote-surfaces"))
        val factory = profile.graph as AiStudioGraph.Factory
        val remoteScope = app.scopes.child(profile.graph.scope, "remote-surface-test")
        val remote = factory.createAiStudio(remoteScope, StudioScreenHost.Remote) as TestStudioSurfaceAccessors
        assertSame(StudioSurfaces.None, remote.surfaces)
        val nativeScope = app.scopes.child(profile.graph.scope, "window-surface-test")
        val native = factory.createAiStudio(nativeScope, StudioScreenHost.Window) as TestStudioSurfaceAccessors
        assertTrue(native.surfaces !== StudioSurfaces.None)
        assertTrue(native.surfaces.availability.first())
        app.profileSessions.close()
    }

    @Test
    fun `the profile machine starts with the profile, keeps bindings and follows its toggle`() = runTest {
        assertTrue(ServiceUiEnabled in toggles.toggleControl.registered)
        assertTrue(ServiceUiEnabled.default)
        val profile = app.profileSessions.open(ProfileId("service-ui"))
        val access = profile.graph as TestServiceUiAccessors
        val host = UiHostId("test.pane.1")
        withContext(app.dispatchers.default) {
            withTimeout(10.seconds) {
                val machine = access.machines.observe(ServiceUiMachineKey).filterNotNull().first()
                machine.state.filterIsInstance<ServiceUiState.Disabled>().first()
                val context = UiHostContext(UiCallContext(), UiDockId("test"))
                assertEquals(SendResult.Accepted, machine.send(ServiceUiIntent.Public.BindHost(host, context)))

                toggles.toggleControl.setOverride(ServiceUiEnabled, true)
                val ready = machine.state.filterIsInstance<ServiceUiState.Ready>().first()
                assertEquals(UiLibrary(), ready.library)
                assertEquals(setOf(host), ready.hosts.keys)

                toggles.toggleControl.setOverride(ServiceUiEnabled, false)
                val disabled = machine.state.filterIsInstance<ServiceUiState.Disabled>().first()
                assertEquals(setOf(host), disabled.hosts.keys)
            }
        }
        val kinds = access.serviceUiProviders.map { it.kind }
        assertEquals(kinds.toSet().size, kinds.size)
        assertTrue("harness" in kinds, kinds.toString())
        app.profileSessions.close()
    }

    @Test
    fun `slot, panel, card, library and editor routes build their places from the profile graph`() = runTest {
        val profile = app.profileSessions.open(ProfileId("service-ui-places"))
        val lifecycle = LifecycleRegistry().apply { resume() }
        try {
            val slot = ServiceUiSlotRoute("test.pane.1", UiSlot.Composer)
            val root = (profile.graph as ProfileNavigation).navigation.create(
                DefaultComponentContext(lifecycle),
                listOf(slot),
                name = "service-ui",
            )
            assertEquals(slot, root.stack.value.active.configuration.route)
            assertIs<ComposableComponent>(root.stack.value.active.instance)

            val panel = ServiceUiPanelRoute("test")
            root.navigator.navigate(panel)
            assertEquals(panel, root.stack.value.active.configuration.route)
            assertIs<ComposableComponent>(root.stack.value.active.instance)

            val card = ServiceUiCardRoute("c_unknown")
            root.navigator.navigate(card)
            assertEquals(card, root.stack.value.active.configuration.route)
            assertIs<ComposableComponent>(root.stack.value.active.instance)

            val library = ServiceUiLibraryRoute(isEmbedded = true)
            root.navigator.navigate(library)
            assertEquals(library, root.stack.value.active.configuration.route)
            assertIs<ComposableComponent>(root.stack.value.active.instance)

            val editor = ServiceUiSurfaceRoute("harness:h_unknown:map")
            root.navigator.navigate(editor)
            assertEquals(editor, root.stack.value.active.configuration.route)
            assertIs<ComposableComponent>(root.stack.value.active.instance)
        } finally {
            lifecycle.destroy()
            app.profileSessions.close()
        }
    }
}

@ContributesTo(ProfileScope::class)
interface TestServiceUiAccessors {
    val machines: MachineRegistry
    val serviceUiProviders: Set<ServiceUiProvider>
}

@ContributesTo(AiStudioScope::class)
interface TestStudioSurfaceAccessors {
    val surfaces: StudioSurfaces
}
