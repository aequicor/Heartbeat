package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiPanelRoute
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiSlotRoute
import io.aequicor.heartbeat.feature.serviceasui.api.UiDockId
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostId

/**
 * The place a route names, checked by its route entry: a slot of a host place or the panel of a dock. A route naming
 * a malformed place — a bug of its host — leaves [target] null and the place empty.
 */
@ConsistentCopyVisibility
data class ServiceUiPlace internal constructor(internal val target: PlaceTarget?, internal val isPanel: Boolean) {
    internal companion object {
        private val log = Log.tag("ServiceUiPlace")

        /** The slot [ServiceUiSlotRoute.slot] of the host place of [route]. */
        fun of(route: ServiceUiSlotRoute): ServiceUiPlace =
            ServiceUiPlace(checked { PlaceTarget.Slot(UiHostId(route.host), route.slot) }, isPanel = false)

        /** The panel of the dock of [route]. */
        fun of(route: ServiceUiPanelRoute): ServiceUiPlace =
            ServiceUiPlace(checked { PlaceTarget.Panel(UiDockId(route.dock)) }, isPanel = true)

        private fun checked(target: () -> PlaceTarget): PlaceTarget? = try {
            target()
        } catch (e: IllegalArgumentException) {
            log.e(e) { "Route of a surface place names an invalid place" }
            null
        }
    }
}
