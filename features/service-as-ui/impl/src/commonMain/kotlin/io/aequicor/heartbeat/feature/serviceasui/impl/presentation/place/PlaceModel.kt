package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurface
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceId
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceName
import io.aequicor.heartbeat.feature.serviceasui.impl.di.scope.ServiceUiPlaceScope
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiMachine
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiMethodCalls
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecDecoding
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.decodeUiSpec
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiFacts
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.platformFact
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceHost
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceModel
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceSession
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed

private typealias PlacePipeline = PipelineContext<PlaceState, PlaceIntent, PlaceAction>

/**
 * The surfaces of one place: a slot of a host place or the panel of a dock.
 *
 * The store does not `reflect` the machine alone: what a place shows also depends on the owners its providers report
 * active in the place's context and on the modal opened here, and showing it starts and stops the stores of drawn
 * surfaces. Each drawn surface gets its own store in a child scope, started when it appears and closed when it leaves
 * or changes; only the observing coroutine touches that registry, and a new subscription replaces it after the old one
 * ended. Launchers and owner surfaces open as a panel in the host's dock or, without a dock or for modal surfaces, as
 * the modal over the place, which closes for good once its surface, owner or host leaves the place.
 */
@SingleIn(ServiceUiPlaceScope::class)
@Inject
internal class PlaceModel(
    place: ServiceUiPlace,
    private val machine: ServiceUiMachine,
    private val calls: UiMethodCalls,
    private val surfaceModels: SurfaceModel.Factory,
    platformInfo: PlatformInfo,
    factory: HeartbeatStoreFactory,
    @ForScope(ServiceUiPlaceScope::class) handle: ScopeHandle,
) {
    private val log = Log.tag("ServiceUiPlace")
    private val target = place.target
    private val scope = handle.coroutineScope
    private val platform = platformFact(platformInfo.host)
    private val modal = MutableStateFlow<PlacedSurface?>(null)
    private val active = MutableStateFlow(ActiveOwners())
    private val running = mutableMapOf<PlacedSurface, Running>()
    private val unreadable = mutableSetOf<PlacedSurface>()
    private val models = MutableStateFlow<ImmutableMap<String, SurfaceModel>>(persistentMapOf())
    private var drawings = 0L

    /** Whether this place is the panel of a dock rather than a slot. */
    val isPanel: Boolean = place.isPanel

    /** Stores of the drawn surfaces and the modal one, by [PlaceSurfaceUi.key]. */
    val surfaces: StateFlow<ImmutableMap<String, SurfaceModel>> = models.asStateFlow()

    val store: Store<PlaceState, PlaceIntent, PlaceAction> = factory.create<PlaceState, PlaceIntent, PlaceAction>(
        name = "ServiceUiPlace",
        initial = PlaceState(),
        onError = { this },
    ) {
        whileSubscribed { Work(this).observe() }
        reduce { intent -> Work(this).handle(intent) }
    }

    init {
        store.start(scope)
    }

    /** Owners a provider reported active in [context] (null before the place shows a host). */
    private data class ActiveOwners(val context: UiCallContext? = null, val owners: Set<UiOwner> = emptySet())

    /** What the place shows: its [content] and the [modal] still valid of the [requested] one, for [active] owners. */
    private data class Shown(
        val content: PlaceContent,
        val modal: PlacedSurface?,
        val requested: PlacedSurface?,
        val active: ActiveOwners,
    )

    /** A started surface store, its scope and the [key] of this drawing. */
    private data class Running(val model: SurfaceModel, val scope: CoroutineScope, val key: String)

    /** Work of one intent or subscription on the store [pipeline]. */
    private inner class Work(private val pipeline: PlacePipeline) {
        @OptIn(ExperimentalCoroutinesApi::class)
        suspend fun observe() {
            val target = target ?: return
            val reported = machine.state
                .map { target.host(it)?.second?.call }
                .distinctUntilChanged()
                .flatMapLatest { call ->
                    call?.let { calls.activeOwners(it).map { owners -> ActiveOwners(call, owners) } }
                        ?: flowOf(ActiveOwners())
                }
            combine(machine.state, reported, modal) { state, owners, open ->
                // Rebinding revokes the old surfaces immediately, including their running actions, while the new
                // provider snapshot is still pending. Keeping the old state would leave another chat's UI live.
                val context = target.host(state)?.second?.call
                if (owners.context != context) {
                    return@combine Shown(PlaceContent(), null, open, ActiveOwners(context))
                }
                val shown = open?.let { target.modal(state, owners.owners, it) }
                Shown(target.content(state, owners.owners), shown, open, owners)
            }.distinctUntilChanged().collect { show(it) }
        }

        suspend fun handle(intent: PlaceIntent) {
            when (intent) {
                is PlaceIntent.Launch -> launch(intent.surface)

                PlaceIntent.CloseModal -> {
                    log.i { "Surface modal closed" }
                    modal.value = null
                }

                PlaceIntent.ClosePanel -> (target as? PlaceTarget.Panel)?.let { panel ->
                    log.i { "Close panel ${panel.dock.value}" }
                    pipeline.sendTo(machine, ServiceUiIntent.Public.ClosePanel(panel.dock))
                }
            }
        }

        private suspend fun show(shown: Shown) {
            active.value = shown.active
            // A modal whose surface, owner or host left the place closes for good instead of coming back later.
            if (shown.requested != null && shown.modal == null) modal.compareAndSet(shown.requested, null)
            keep(shown.content.surfaces + listOfNotNull(shown.modal))
            pipeline.updateState {
                PlaceState(
                    surfaces = shown.content.surfaces.mapNotNull { ui(it) }.toImmutableList(),
                    launchers = shown.content.launchers.map { LauncherUi(it.id.value, it.content.title) }
                        .toImmutableList(),
                    modal = shown.modal?.let { ui(it) },
                    panelTitle = shown.content.panelTitle,
                )
            }
        }

        private suspend fun launch(surface: String) {
            val target = target ?: return
            val state = machine.state.value
            val (host, context) = target.host(state) ?: return
            val owners = active.value.takeIf { it.context == context.call }?.owners ?: return
            val launched = target.content(state, owners).launchers.firstOrNull { it.id.value == surface } ?: return
            log.i { "Launch surface $surface" }
            if (launched.content.placement == UiPlacement.Panel && context.dock != null) {
                pipeline.sendTo(machine, ServiceUiIntent.Public.OpenPanel(host, launched.id, expectedContext = context))
            } else {
                modal.value = PlacedSurface(PlaceRole.Modal, launched, host, context)
            }
        }
    }

    private fun ui(placed: PlacedSurface): PlaceSurfaceUi? =
        running[placed]?.let { PlaceSurfaceUi(it.key, placed.surface.content.title) }

    /** Opens the surface [name] of the owner of [from] as the modal over the place, in its context, with [args]. */
    private fun openOver(from: PlacedSurface, name: String, args: JsonObject): Boolean {
        if (!isCurrent(from)) return false
        val surface = surfaceOver(from.surface.content.owner, name) ?: return false
        log.i { "Open surface ${surface.id.value} over the place" }
        modal.value = PlacedSurface(PlaceRole.Modal, surface, from.host, from.context, args)
        return true
    }

    /** The surface [name] of [owner] when it may open as a modal or a panel here: not inline, enabled and active. */
    private fun surfaceOver(owner: UiOwner, name: String): UiSurface? {
        val id = try {
            UiSurfaceId.of(owner, UiSurfaceName(name))
        } catch (e: IllegalArgumentException) {
            log.w(e) { "Surface name of ${owner.kind} ${owner.id} is invalid" }
            return null
        }
        val library = (machine.state.value as? ServiceUiState.Ready)?.library ?: return null
        return library[id]?.takeIf { it.isShownOver(active.value.owners) }
    }

    /** Starts stores for [drawn] surfaces that are new and closes those that left; unreadable specs are tried once. */
    private fun keep(drawn: List<PlacedSurface>) {
        val wanted = drawn.toSet()
        unreadable.retainAll(wanted)
        val gone = running.keys - wanted
        val new = wanted.filter { it !in running && it !in unreadable }
        if (gone.isEmpty() && new.isEmpty()) return
        gone.forEach { placed -> running.remove(placed)?.scope?.cancel() }
        new.forEach { placed -> start(placed)?.let { running[placed] = it } ?: unreadable.add(placed) }
        log.d { "Place surfaces: ${new.size} new, ${gone.size} closed, ${running.size} drawn" }
        models.value = running.values.associate { it.key to it.model }.toImmutableMap()
    }

    private fun start(placed: PlacedSurface): Running? {
        val spec = (decodeUiSpec(placed.surface.content.spec) as? UiSpecDecoding.Decoded)?.spec
        if (spec == null) {
            log.w { "Surface ${placed.surface.id.value} has an unreadable spec" }
            return null
        }
        val child = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val session = SurfaceSession(
            spec = spec,
            owner = placed.surface.content.owner,
            context = placed.context.call,
            args = placed.args,
            facts = UiFacts(platform, hasSession = placed.context.call.session != null),
            host = PlaceSurfaceHost(placed),
            title = placed.surface.content.title,
        )
        return try {
            Running(surfaceModels.create(session, child), child, "drawing-${++drawings}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e.redactedFailure()) { "Surface ${placed.surface.id.value} could not start" }
            child.cancel()
            null
        }
    }

    /** A retained or finishing action belongs only to the still-drawn surface in its original host context. */
    private fun isCurrent(placed: PlacedSurface): Boolean {
        val state = machine.state.value as? ServiceUiState.Ready ?: return false
        return placed in running && target?.host(state) == (placed.host to placed.context) &&
            state.library[placed.surface.id] == placed.surface &&
            active.value.context == placed.context.call && placed.surface.content.owner in active.value.owners
    }

    /** Prompts go to the host place of the surface; panels open in its dock, or over the place without one. */
    private inner class PlaceSurfaceHost(private val placed: PlacedSurface) : SurfaceHost {
        override suspend fun prompt(text: String, isSubmitted: Boolean): Boolean {
            if (!isCurrent(placed)) return false
            val request = ServiceUiIntent.Internal.RequestPrompt(placed.host, text, isSubmitted, placed.context)
            return machine.send(request) == SendResult.Accepted
        }

        override suspend fun openPanel(name: String, args: JsonObject): Boolean {
            if (!isCurrent(placed)) return false
            if (placed.context.dock == null) return openOver(placed, name, args)
            val surface = surfaceOver(placed.surface.content.owner, name) ?: return false
            val request = ServiceUiIntent.Public.OpenPanel(placed.host, surface.id, args, placed.context)
            return machine.send(request) == SendResult.Accepted
        }

        override suspend fun openSurface(name: String, args: JsonObject): Boolean = openOver(placed, name, args)
    }
}
