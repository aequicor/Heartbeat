package io.aequicor.heartbeat.chromiumplugin.panel.store

import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIState

/** How the panel follows its studio view in the app. */
internal enum class StudioViewStatus {
    /** Waiting for the first snapshot (or a new one after a lost view). */
    Connecting,

    /** Frames arrive. */
    Live,

    /** The app does not offer studio views now (toggle off or no studio in the profile). */
    Unavailable,

    /** The extension was built from other sources than the app: their screen models differ. */
    Incompatible,

    /** The app already serves its limit of views. */
    Busy,
}

/** The studio drawn in the panel: [screen] stays visible while the view reconnects. */
internal data class RemoteStudioState(
    val status: StudioViewStatus = StudioViewStatus.Connecting,
    val screen: AiStudioScreenState? = null,
) : MVIState

/** The remote studio has no one-off effects; the app's screen store has none either. */
internal sealed interface RemoteStudioAction : MVIAction
