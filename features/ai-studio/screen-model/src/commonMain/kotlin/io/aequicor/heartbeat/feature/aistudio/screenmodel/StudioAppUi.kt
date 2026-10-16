@file:UseSerializers(ImmutableSetSerializer::class)

package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * Native launcher metadata for one pane's canonical app. The captured pane context hides a queued old app as soon
 * as the user changes chats, projects or new-chat harness choices. The app's navigation component owns its body.
 */
@Immutable
@Serializable
data class StudioAppUi(
    val surfaceId: String,
    val title: String,
    val harnessId: String?,
    val sessionId: String?,
    val projectId: String?,
    val selectedHarnesses: ImmutableSet<String> = persistentSetOf(),
)

/** Only a selection for the currently displayed pane can change its layout or offer an app action. */
fun AiStudioScreenState.appFor(pane: PaneUi): StudioAppUi? {
    val app = apps[pane.id] ?: return null
    val selected = if (pane.sessionId == null || app.selectedHarnesses.isNotEmpty()) {
        surfaceHarnesses(pane)
    } else {
        emptySet()
    }
    return app.takeIf {
        it.sessionId == pane.sessionId && it.projectId == pane.projectId && it.selectedHarnesses == selected
    }
}

/**
 * The displayed first submission owns its original harness choice until native acceptance. Submission removes that
 * choice from the next draft before the native chat exists; surfaces must not mistake this for owner deselection.
 * Navigation marks the old submission undisplayed, so its claim cannot follow another pane or chat.
 */
fun AiStudioScreenState.surfaceHarnesses(pane: PaneUi): Set<String> {
    val pending = submissions.values.firstOrNull {
        it.paneId == pane.id && it.isDisplayed && it.draftKey.startsWith("submission:")
    }
    val isSameChat = pane.sessionId == null || pending?.sessionId == null || pending.sessionId == pane.sessionId
    return when {
        pending != null && isSameChat -> pending.harnesses
        pane.sessionId == null -> selectedHarnesses(pane)
        else -> emptySet()
    }
}
