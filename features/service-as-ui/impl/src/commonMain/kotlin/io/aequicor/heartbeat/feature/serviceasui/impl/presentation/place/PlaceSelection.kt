package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place

import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.UiDockId
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostId
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import io.aequicor.heartbeat.feature.serviceasui.api.UiSlot
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurface
import kotlinx.serialization.json.JsonObject

/** A place that draws surfaces: an inline [Slot] of a host place, or the [Panel] open in a dock. */
internal sealed interface PlaceTarget {
    /** Inline surfaces and launchers of [slot] in the host place [host]. */
    data class Slot(val host: UiHostId, val slot: UiSlot) : PlaceTarget

    /** The panel open in [dock]. */
    data class Panel(val dock: UiDockId) : PlaceTarget
}

/** How a place draws a surface: in its body, or as the modal over it. */
internal enum class PlaceRole { Body, Modal }

/**
 * The library [surface] drawn in [role] for the host place [host] in its [context], opened with [args]. Equal values
 * are the same drawn surface; another role, revision, context or argument set starts it over.
 */
internal data class PlacedSurface(
    val role: PlaceRole,
    val surface: UiSurface,
    val host: UiHostId,
    val context: UiHostContext,
    val args: JsonObject = JsonObject(emptyMap()),
) {
    override fun toString(): String = "PlacedSurface(role=$role, surface=${surface.id.value}, host=${host.value})"
}

/**
 * What a place shows: [surfaces] drawn in its body and [launchers] of panel and modal surfaces, all working in the
 * [context] of the host place [host]; [panelTitle] names the open panel.
 */
internal data class PlaceContent(
    val host: UiHostId? = null,
    val context: UiHostContext = UiHostContext(),
    val surfaces: List<PlacedSurface> = emptyList(),
    val launchers: List<UiSurface> = emptyList(),
    val panelTitle: String? = null,
) {
    override fun toString(): String =
        "PlaceContent(host=${host?.value ?: "none"}, surfaces=${surfaces.size}, launchers=${launchers.size})"
}

/**
 * The host place whose context the surfaces of this place use, or null while the place shows nothing: before the
 * library is ready, before a slot's place is bound, or while no panel is open in a dock.
 */
internal fun PlaceTarget.host(state: ServiceUiState): Pair<UiHostId, UiHostContext>? = when (state) {
    is ServiceUiState.Ready -> when (this) {
        is PlaceTarget.Slot -> state.hosts[host]?.let { host to it }

        is PlaceTarget.Panel -> state.panels[dock]?.let { panel ->
            panel.host to
                (state.hosts[panel.host] ?: UiHostContext())
        }
    }

    is ServiceUiState.Disabled, is ServiceUiState.Loading, is ServiceUiState.Failed -> null
}

/**
 * What this place shows in [state] while the owners [active] are active in its context. A slot draws the enabled
 * surfaces of its placement and the launchers that name it, oldest first; a panel draws the enabled surface open in
 * its dock. Surfaces of owners that are not active do not show.
 */
internal fun PlaceTarget.content(state: ServiceUiState, active: Set<UiOwner>): PlaceContent = when (state) {
    is ServiceUiState.Ready -> readyContent(state, active)
    is ServiceUiState.Disabled, is ServiceUiState.Loading, is ServiceUiState.Failed -> PlaceContent()
}

/**
 * The modal [open] as this place shows it in [state]: with the current revision of its surface and the current
 * context of the place, or null once the place shows another host, or the surface is gone, disabled, inline or of an
 * owner that is not [active].
 */
internal fun PlaceTarget.modal(state: ServiceUiState, active: Set<UiOwner>, open: PlacedSurface): PlacedSurface? {
    val context = host(state)?.takeIf { it.first == open.host }?.second ?: return null
    val surface = library(state)?.get(open.surface.id)?.takeIf { it.isShownOver(active) } ?: return null
    return open.copy(surface = surface, context = context)
}

/** Whether [surface] may open as a modal: enabled, not inline and of an owner that is [active]. */
internal fun UiSurface.isShownOver(active: Set<UiOwner>): Boolean =
    isEnabled && (content.placement == UiPlacement.Panel || content.placement == UiPlacement.Modal) &&
        content.owner in active

private fun library(state: ServiceUiState) = when (state) {
    is ServiceUiState.Ready -> state.library
    is ServiceUiState.Disabled, is ServiceUiState.Loading, is ServiceUiState.Failed -> null
}

private fun PlaceTarget.readyContent(state: ServiceUiState.Ready, active: Set<UiOwner>): PlaceContent {
    val (host, context) = host(state) ?: return PlaceContent()
    val appOwner = state.apps[host]?.surface?.let { state.library[it]?.content?.owner }
    val shown = state.library.surfaces
        .filter { it.isEnabled && it.content.owner in active }
        .sortedWith(compareBy({ it.createdAt }, { it.id.value }))
    return when (this) {
        is PlaceTarget.Slot -> PlaceContent(
            host = host,
            context = context,
            surfaces = shown.filter {
                it.content.placement == slot.placement &&
                    (slot == UiSlot.Context || it.content.owner != appOwner)
            }
                .map { PlacedSurface(PlaceRole.Body, it, host, context) },
            launchers = shown.filter { slot in it.content.launchers },
        )

        is PlaceTarget.Panel -> {
            val panel = state.panels.getValue(dock)
            val surface = shown.firstOrNull { it.id == panel.surface }
            PlaceContent(
                host = host,
                context = context,
                surfaces = listOfNotNull(surface?.let { PlacedSurface(PlaceRole.Body, it, host, context, panel.args) }),
                panelTitle = surface?.content?.title,
            )
        }
    }
}
