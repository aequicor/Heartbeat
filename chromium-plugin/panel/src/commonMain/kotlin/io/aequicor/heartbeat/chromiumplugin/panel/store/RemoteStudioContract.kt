package io.aequicor.heartbeat.chromiumplugin.panel.store

import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
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

    /** The view exceeds the bounded receiver; awaiting a smaller snapshot on the same watch. */
    TooLarge,
}

/** The studio drawn in the panel: [screen] stays visible while the view reconnects. */
internal data class RemoteStudioState(
    val status: StudioViewStatus = StudioViewStatus.Connecting,
    val screen: AiStudioScreenState? = null,
    /** Generation of the mirrored screen, used to bind asynchronous browser capture. */
    val generation: String? = null,
    /** Local inputs settled by a frame, including those discarded with a lost generation. */
    val settledInputs: Long = 0,
    /** Transport epoch whose snapshot established the live mirror. */
    val connectionEpoch: Long? = null,
    /** Previous screen retained after the app recreated the view; actions were not replayed. */
    val interruptedScreen: AiStudioScreenState? = null,
) : MVIState

/** The remote studio has no one-off effects; the app's screen store has none either. */
internal sealed interface RemoteStudioAction : MVIAction
