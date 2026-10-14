package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfaceCardFocus
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
        .flatMapLatest { machine ->
            machine?.outputs?.filterIsInstance<ServiceUiOutput.PromptRequested>()?.mapNotNull { output ->
                val current = machine.state.value as? ServiceUiState.Ready
                if (current?.hosts?.get(output.host) != output.context) return@mapNotNull null
                paneOf(output.host)?.let {
                    StudioSurfacePrompt(
                        it,
                        output.text,
                        output.isSubmitted,
                        StudioSurfacePlace(
                            chat = output.context.call.session,
                            workspace = output.context.call.workspace,
                            harnesses = output.context.call.preselected[HarnessTools.GROUP].orEmpty(),
                        ),
                    )
                }
            } ?: emptyFlow()
        }

    override fun host(paneId: Int): String = "$prefix.pane-$paneId"

    override val cardFocus: Flow<StudioSurfaceCardFocus> = machines.observe(ServiceUiMachineKey)
        .flatMapLatest { machine ->
            machine?.outputs?.filterIsInstance<ServiceUiOutput.CardFocusRequested>()?.mapNotNull { output ->
                val current = machine.state.value as? ServiceUiState.Ready
                if (current?.hosts?.get(output.host) != output.context) return@mapNotNull null
                paneOf(output.host)?.let { pane ->
                    StudioSurfaceCardFocus(
                        pane,
                        output.cardId,
                        StudioSurfacePlace(
                            chat = output.context.call.session,
                            workspace = output.context.call.workspace,
                            harnesses = output.context.call.preselected[HarnessTools.GROUP].orEmpty(),
                        ),
                    )
                }
            } ?: emptyFlow()
        }

    override suspend fun closePanel() {
        log.i { "Close the interface panel of the studio" }
        // A panel already closed meanwhile ignores this; the runtime logs it.
        machines.send(ServiceUiMachineKey, ServiceUiIntent.Public.ClosePanel(side))
    }

    override suspend fun keep(places: Flow<Map<Int, StudioSurfacePlace>>) {
        val held = mutableMapOf<Int, UiHostContext>()
        // Panes whose bind was sent: a bind cut short by cancellation may still have been taken.
        val touched = mutableSetOf<Int>()
        var machine: SurfaceMachine? = null
        try {
            combine(places, availability, machines.observe(ServiceUiMachineKey)) { panes, isOn, ref ->
                Triple(panes, isOn, ref)
            }.collect { (panes, isOn, ref) ->
                // A restarted machine knows no places of this window.
                if (ref !== machine) {
                    held.clear()
                    touched.clear()
                }
                machine = ref
                val wanted = if (isOn && ref != null) panes.mapValues { (_, place) -> place.context() } else emptyMap()
                ref?.let { reconcile(it, held, touched, wanted) }
            }
        } finally {
            val last = machine
            val left = held.keys + touched
            if (last != null && left.isNotEmpty()) {
                withContext(NonCancellable) {
                    left.forEach { last.send(ServiceUiIntent.Public.UnbindHost(placeOf(it))) }
                }
            }
        }
    }

    /** Unbinds gone places and binds changed ones, recording in [held] what the machine holds. */
    private suspend fun reconcile(
        machine: SurfaceMachine,
        held: MutableMap<Int, UiHostContext>,
        touched: MutableSet<Int>,
        wanted: Map<Int, UiHostContext>,
    ) {
        val before = held.size
        (held.keys + touched - wanted.keys).forEach { pane ->
            // Ignored means the place is not bound anymore, which is what unbinding wants.
            if (machine.send(ServiceUiIntent.Public.UnbindHost(placeOf(pane))) != SendResult.NotRunning) {
                held -= pane
                touched -= pane
            }
        }
        wanted.filter { (pane, context) -> held[pane] != context }.forEach { (pane, context) ->
            touched += pane
            if (machine.send(ServiceUiIntent.Public.BindHost(placeOf(pane), context)) == SendResult.Accepted) {
                held[pane] = context
            }
        }
        if (held.size != before) log.d { "Studio places bound=${held.size}" }
    }

    private fun placeOf(pane: Int) = UiHostId(host(pane))

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
