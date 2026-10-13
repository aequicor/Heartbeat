package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfacePlace
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfacePrompt
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfaces
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiEnabled
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiMachineKey
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiOutput
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiDockId
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

private typealias SurfaceMachine = MachineRef<ServiceUiState, ServiceUiIntent.Public, ServiceUiOutput>

/**
 * Panes of one studio window as places of the interface machine. Place ids carry a prefix of this window, so two
 * windows never share a place; every pane uses the window's side dock for panels. A new chat offers the harnesses
 * selected for it, so their interfaces show in its start window before the chat exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SingleIn(AiStudioScope::class)
@ContributesBinding(AiStudioScope::class, binding = binding<StudioSurfaces>())
@Inject
internal class MachineStudioSurfaces(private val machines: MachineRegistry, toggles: FeatureToggles) :
    StudioSurfaces {
    private val log = Log.tag("StudioSurfaces")
    private val prefix = "studio-${Uuid.random().toHexString().take(PREFIX_CHARS)}"
    private val side = UiDockId("$prefix.side")

    override val dock: String get() = side.value

    override val panel: Flow<Long?> = machines.observe(ServiceUiMachineKey)
        .flatMapLatest { it?.state ?: flowOf(null) }
        .map { (it as? ServiceUiState.Ready)?.panels?.get(side)?.sequence }
        .distinctUntilChanged()

    override val availability: Flow<Boolean> = toggles.observe(ServiceUiEnabled)

    override val prompts: Flow<StudioSurfacePrompt> = machines.observe(ServiceUiMachineKey)
        .flatMapLatest { it?.outputs ?: emptyFlow() }
        .filterIsInstance<ServiceUiOutput.PromptRequested>()
        .mapNotNull { output ->
            paneOf(output.host)?.let { StudioSurfacePrompt(it, output.text, output.isSubmitted) }
        }

    override fun host(paneId: Int): String = "$prefix.pane-$paneId"

    override suspend fun closePanel() {
        log.i { "Close the interface panel of the studio" }
        val result = machines.send(ServiceUiMachineKey, ServiceUiIntent.Public.ClosePanel(side))
        if (result != SendResult.Accepted) log.w { "Interface panel not closed: $result" }
    }

    override suspend fun keep(places: Flow<Map<Int, StudioSurfacePlace>>) {
        var bound = emptyMap<Int, UiHostContext>()
        var machine: SurfaceMachine? = null
        try {
            combine(places, availability, machines.observe(ServiceUiMachineKey)) { panes, isOn, ref ->
                Triple(panes, isOn, ref)
            }.collect { (panes, isOn, ref) ->
                // A restarted machine knows no places of this window.
                if (ref !== machine) bound = emptyMap()
                machine = ref
                val wanted = if (isOn && ref != null) panes.mapValues { (_, place) -> place.context() } else emptyMap()
                bound = ref?.let { reconcile(it, bound, wanted) }.orEmpty()
            }
        } finally {
            val last = machine
            if (last != null && bound.isNotEmpty()) withContext(NonCancellable) { reconcile(last, bound, emptyMap()) }
        }
    }

    /** Binds changed places and unbinds gone ones; returns what the machine now holds. */
    private suspend fun reconcile(
        machine: SurfaceMachine,
        bound: Map<Int, UiHostContext>,
        wanted: Map<Int, UiHostContext>,
    ): Map<Int, UiHostContext> {
        val held = bound.toMutableMap()
        (bound.keys - wanted.keys).forEach { pane ->
            if (machine.send(ServiceUiIntent.Public.UnbindHost(UiHostId(host(pane)))) == SendResult.Accepted) {
                held -= pane
            }
        }
        wanted.filter { (pane, context) -> bound[pane] != context }.forEach { (pane, context) ->
            val result = machine.send(ServiceUiIntent.Public.BindHost(UiHostId(host(pane)), context))
            if (result == SendResult.Accepted) held[pane] = context else log.w { "Pane $pane place not bound: $result" }
        }
        if (held != bound) log.d { "Studio places bound=${held.size}" }
        return held
    }

    private fun StudioSurfacePlace.context(): UiHostContext {
        // A new chat offers the harnesses selected for it; a chat has its own connections.
        val selected = harnesses.takeIf { chat == null && it.isNotEmpty() }
        val preselected = selected?.let { mapOf(HarnessTools.GROUP to it) }.orEmpty()
        return UiHostContext(UiCallContext(session = chat, workspace = workspace, preselected = preselected), side)
    }

    private fun paneOf(host: UiHostId): Int? =
        host.value.removePrefix("$prefix.pane-").takeIf { it != host.value }?.toIntOrNull()

    private companion object {
        const val PREFIX_CHARS = 8
    }
}
