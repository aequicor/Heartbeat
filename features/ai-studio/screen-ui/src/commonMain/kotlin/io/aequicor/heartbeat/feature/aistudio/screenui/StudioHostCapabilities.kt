package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.runtime.Immutable

/**
 * Surfaces the screen's host can present. Domain choices still come from the screen state; these switches only
 * remove native window affordances from a browser host. [onAttachPage] captures into the requested pane.
 */
@Immutable
data class StudioHostCapabilities(
    val isNativeAttachmentInputAvailable: Boolean = true,
    val isAttachmentOpeningAvailable: Boolean = true,
    val isProjectAddingAvailable: Boolean = true,
    val isSplitAvailable: Boolean = true,
    val isOrganismLaunchingAvailable: Boolean = true,
    val isAppUpdateAvailable: Boolean = true,
    val onAttachPage: ((Int) -> Unit)? = null,
)
