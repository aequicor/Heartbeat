package io.aequicor.heartbeat.chromiumplugin.panel.store

import io.aequicor.heartbeat.feature.chromiumbridge.protocol.WirePage
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

/** Browser-only presentation state around the shared studio screen. */
internal data class StudioPanelState(
    val remote: RemoteStudioState = RemoteStudioState(),
    val isConnected: Boolean = false,
    val isCapturing: Boolean = false,
    val capturedPage: WirePage? = null,
    val captureProblem: CaptureProblem? = null,
    val recoveryDraft: String? = null,
) : MVIState {
    /** Stale frames remain readable, but only a live view on a connected bridge accepts user input. */
    val isInteractive: Boolean get() = isConnected && remote.status == StudioViewStatus.Live
}

/** Browser operations that could not attach the page; the captured content stays available for another attempt. */
internal enum class CaptureProblem { Unreadable, Unsupported, DestinationChanged }

/** Inputs of the panel host; screen input is forwarded unchanged to the shared remote store. */
internal sealed interface StudioPanelIntent : MVIIntent {
    data class Screen(val input: StudioIntent) : StudioPanelIntent
    data class CapturePage(val paneId: Int) : StudioPanelIntent
    data object AttachCaptured : StudioPanelIntent
    data object DismissCapture : StudioPanelIntent
    data object NewView : StudioPanelIntent
    data object RecoverDraft : StudioPanelIntent
    data object DismissRecovery : StudioPanelIntent
}

/** Browser host operations are represented in state. */
internal sealed interface StudioPanelAction : MVIAction
