package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place

import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import io.aequicor.heartbeat.feature.serviceasui.api.UiSlot
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurface
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceContent
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceName
import kotlin.time.Instant

internal val PrMap = UiOwner("harness", "h_1")
internal val Other = UiOwner("harness", "h_2")

internal const val HELLO = """{"version":1,"body":{"type":"column","children":[
    {"type":"text","text":"Hello"},
    {"type":"button","label":"Ask","action":{"type":"prompt","text":"Rank PRs","submit":true}},
    {"type":"button","label":"Map","action":{"type":"open","surface":"map","presentation":"panel"}},
    {"type":"button","label":"Details","action":{"type":"open","surface":"details"}}]}}"""

/** A stored surface of [owner] created at second [at]. */
internal fun surface(
    name: String,
    placement: UiPlacement,
    owner: UiOwner = PrMap,
    launchers: Set<UiSlot> = emptySet(),
    isEnabled: Boolean = true,
    revision: Long = 1,
    at: Long = 0,
): UiSurface = UiSurface(
    content = UiSurfaceContent(
        owner,
        UiSurfaceName(name),
        placement,
        name.uppercase(),
        launchers = launchers,
        spec = HELLO,
    ),
    isEnabled = isEnabled,
    revision = revision,
    createdAt = Instant.fromEpochSeconds(at),
    updatedAt = Instant.fromEpochSeconds(at),
)
