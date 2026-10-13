package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
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
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfacePlace
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfacePrompt
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiEnabled
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiMachineKey
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiOutput
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.UiDockId
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostId
import io.aequicor.heartbeat.feature.serviceasui.api.UiPanel
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MachineStudioSurfacesTest {
    private val native = SessionRef(EngineId("engine"), SessionSourceId("source"), "chat")

    @Test
    fun `places follow the panes, are unbound when they leave and when the studio stops`() = runTest {
        val machine = FakeSurfaceMachine()
        val surfaces = MachineStudioSurfaces(Registry(machine), Toggles(true))
        val places = MutableStateFlow(mapOf(0 to StudioSurfacePlace(chat = native), 1 to StudioSurfacePlace()))
        val job = launch { surfaces.keep(places) }
        runCurrent()

        val bound = machine.sent.map { assertIs<ServiceUiIntent.Public.BindHost>(it) }
        assertEquals(listOf(surfaces.host(0), surfaces.host(1)), bound.map { it.host.value })
        assertEquals(native, bound.first().context.call.session)
        assertTrue(bound.map { it.context.dock }.distinct().size == 1)

        machine.sent.clear()
        places.value = mapOf(0 to StudioSurfacePlace(chat = native), 2 to StudioSurfacePlace(harnesses = setOf("h")))
        runCurrent()
        assertEquals(ServiceUiIntent.Public.UnbindHost(UiHostId(surfaces.host(1))), machine.sent.first())
        val added = assertIs<ServiceUiIntent.Public.BindHost>(
            machine.sent.single { it is ServiceUiIntent.Public.BindHost },
        )
        assertEquals(mapOf("harness" to setOf("h")), added.context.call.preselected)

        machine.sent.clear()
        job.cancel()
        runCurrent()
        assertEquals(
            setOf(surfaces.host(0), surfaces.host(2)),
            machine.sent.map { assertIs<ServiceUiIntent.Public.UnbindHost>(it).host.value }.toSet(),
        )
    }

    @Test
    fun `places are not bound while interfaces are off`() = runTest {
        val machine = FakeSurfaceMachine()
        val surfaces = MachineStudioSurfaces(Registry(machine), Toggles(false))
        val job = launch { surfaces.keep(MutableStateFlow(mapOf(0 to StudioSurfacePlace()))) }
        runCurrent()
        assertEquals(emptyList(), machine.sent)
        job.cancel()
    }

    @Test
    fun `only prompts for the places of this window reach its panes`() = runTest {
        val machine = FakeSurfaceMachine()
        val surfaces = MachineStudioSurfaces(Registry(machine), Toggles(true))
        val other = MachineStudioSurfaces(Registry(machine), Toggles(true))
        val received = mutableListOf<StudioSurfacePrompt>()
        val job = launch { surfaces.prompts.toList(received) }
        runCurrent()

        machine.outputs.emit(ServiceUiOutput.PromptRequested(UiHostId(surfaces.host(3)), "Rank", isSubmitted = true))
        machine.outputs.emit(ServiceUiOutput.PromptRequested(UiHostId(other.host(3)), "Other", isSubmitted = false))
        runCurrent()
        assertEquals(listOf(StudioSurfacePrompt(3, "Rank", isSubmitted = true)), received)
        job.cancel()
    }

    @Test
    fun `the panel of this window's dock is followed and closed through the machine`() = runTest {
        val machine = FakeSurfaceMachine()
        val surfaces = MachineStudioSurfaces(Registry(machine), Toggles(true))
        val seen = mutableListOf<Long?>()
        val job = launch { surfaces.panel.toList(seen) }
        runCurrent()

        val dock = UiDockId(surfaces.dock)
        val panel = UiPanel(UiSurfaceId("harness:h:map"), UiHostId(surfaces.host(0)), sequence = 4)
        machine.state.value = ServiceUiState.Ready(panels = mapOf(dock to panel))
        runCurrent()
        machine.state.value = ServiceUiState.Ready(panels = mapOf(UiDockId("other.side") to panel))
        runCurrent()
        assertEquals(listOf(null, 4L, null), seen)

        surfaces.closePanel()
        assertEquals(listOf<ServiceUiIntent.Public>(ServiceUiIntent.Public.ClosePanel(dock)), machine.sent)
        job.cancel()
    }

    private class FakeSurfaceMachine : MachineRef<ServiceUiState, ServiceUiIntent.Public, ServiceUiOutput> {
        override val name = "service_ui"
        override val state = MutableStateFlow<ServiceUiState>(ServiceUiState.Disabled())
        override val outputs = MutableSharedFlow<ServiceUiOutput>(extraBufferCapacity = 8)
        val sent = mutableListOf<ServiceUiIntent.Public>()

        override suspend fun send(intent: ServiceUiIntent.Public): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }

    private class Registry(private val machine: FakeSurfaceMachine) : MachineRegistry {
        @Suppress("UNCHECKED_CAST") // The test registry holds the interface machine only.
        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
            key: MachineKey<S, I, P, E, O>,
        ): MachineRef<S, P, O>? = if (key == ServiceUiMachineKey) machine as MachineRef<S, P, O> else null

        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
            key: MachineKey<S, I, P, E, O>,
        ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(find(key))

        override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
            key: MachineKey<S, I, P, E, O>,
            intent: P,
        ): SendResult = find(key)?.send(intent) ?: SendResult.NotRunning
    }

    private class Toggles(private val isOn: Boolean) : FeatureToggles {
        @Suppress("UNCHECKED_CAST") // Only the interface flag is read.
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> =
            MutableStateFlow((if (toggle == ServiceUiEnabled) isOn else toggle.default) as T)

        @Suppress("UNCHECKED_CAST") // Only the interface flag is read.
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T =
            (if (toggle == ServiceUiEnabled) isOn else toggle.default) as T
    }
}
