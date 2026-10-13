package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Content another feature shows inside the studio screen: an agent's questionnaire or checklist card, or a mode
 * that takes over the chat area (research). The screen only draws it; a host without such a feature passes none.
 */
interface StudioEmbed {
    /** Draws the embedded content. */
    @Composable
    fun Content(modifier: Modifier)
}
