package io.aequicor.heartbeat.feature.serviceasui.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.navigation.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Inline surfaces and launchers of [slot] in the host place [host] (a `UiHostId` value). The host embeds the route
 * where the slot belongs and binds the place's context before; an unbound place shows nothing.
 */
@Serializable
@SerialName("service_ui_slot")
public data class ServiceUiSlotRoute(public val host: String, public val slot: UiSlot) : Route

/** The panel open in the dock [dock] (a `UiDockId` value), or nothing while none is open. */
@Serializable
@SerialName("service_ui_panel")
public data class ServiceUiPanelRoute(public val dock: String) : Route

/** A card the agent showed in a response; the host controls its placement inside the message. */
@Serializable
@SerialName("service_ui_card")
public data class ServiceUiCardRoute(public val id: String) : Route

/** The surface library of the profile; [isEmbedded] draws only the content inside the settings window. */
@Serializable
@SerialName("service_ui_library")
public data class ServiceUiLibraryRoute(public val isEmbedded: Boolean = false) : Route

/** Details, editor and preview of the surface [id] (a `UiSurfaceId` value). */
@Serializable
@SerialName("service_ui_surface")
public data class ServiceUiSurfaceRoute(public val id: String) : Route

/** Agent-built interfaces: the surface library, studio slots and panels, and cards in responses. */
public val ServiceUiEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "service_ui.enabled",
    "Интерфейсы агента: библиотека, слоты студии и карточки ответов",
    default = false,
)
