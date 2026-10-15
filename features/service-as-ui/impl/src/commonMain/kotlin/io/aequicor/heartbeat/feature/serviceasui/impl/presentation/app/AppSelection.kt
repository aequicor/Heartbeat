package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app

import io.aequicor.heartbeat.feature.serviceasui.api.UiAppTarget
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiLibrary
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurface
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCard
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardSource

/** The one full app of a host; all entries point at this identity and argument set. */
internal data class AppSelection(val surface: UiSurface, val context: UiHostContext, val target: UiAppTarget)

/**
 * A saved receipt wins even if its surface became unavailable: do not silently replace it with another app.
 * Before the first receipt, the newest enabled Card of active owners supplies the app without requiring a chat.
 */
internal fun selectApp(
    library: UiLibrary,
    context: UiHostContext,
    active: Set<UiOwner>,
    card: UiCard?,
): AppSelection? {
    val source = card?.source as? UiCardSource.Surface
    val surface = if (source != null) {
        library[source.surface]?.takeIf {
            card.chat == context.call.session && it.isEnabled &&
                it.content.placement == UiPlacement.Card && it.content.owner in active
        }
    } else {
        library.surfaces.asSequence()
            .filter { it.isEnabled && it.content.placement == UiPlacement.Card && it.content.owner in active }
            .maxWithOrNull(compareBy({ it.updatedAt }, { it.id.value }))
    } ?: return null
    val target = if (source == null) UiAppTarget(surface.id) else UiAppTarget(surface.id, card.args, card.id)
    return AppSelection(surface, context, target)
}

/** Only the first chat of this selected owner may keep its start DOM; switching actual chats never does. */
internal fun AppSelection.canContinueAs(next: AppSelection): Boolean {
    val before = context.call
    val after = next.context.call
    val owner = surface.content.owner
    return before.session == null && after.session != null && before.workspace == after.workspace &&
        owner.id in before.preselected[owner.kind].orEmpty() && surface == next.surface &&
        target.args == next.target.args
}

/** A new receipt id alone is navigation metadata; it does not change the live surface session. */
internal fun AppSelection.sameSession(other: AppSelection): Boolean =
    surface == other.surface && context == other.context && target.args == other.target.args
