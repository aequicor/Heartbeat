@file:UseSerializers(ImmutableSetSerializer::class)

package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Presence of a context slot for an exact pane selection; its retained navigation component owns the body. */
@Immutable
@Serializable
data class StudioContextPanelUi(
    val sessionId: String?,
    val projectId: String?,
    val selectedHarnesses: ImmutableSet<String> = persistentSetOf(),
)

/** A stale presence snapshot must not narrow another chat or offer an action for its previous owner. */
fun AiStudioScreenState.contextPanelFor(pane: PaneUi): StudioContextPanelUi? {
    val panel = contextPanels[pane.id] ?: return null
    val selected = if (pane.sessionId == null || panel.selectedHarnesses.isNotEmpty()) {
        surfaceHarnesses(pane)
    } else {
        emptySet()
    }
    return panel.takeIf {
        it.sessionId == pane.sessionId && it.projectId == pane.projectId && it.selectedHarnesses == selected
    }
}
