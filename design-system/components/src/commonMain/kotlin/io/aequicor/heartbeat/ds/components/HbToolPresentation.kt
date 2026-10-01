package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.aequicor.heartbeat.ds.theme.HbTheme

/** Whether [this] reports host-owned checkout or build lifecycle rather than an agent call. */
internal val HbToolCall.isWorktree: Boolean get() = kind == HbToolKind.Worktree

/**
 * Edge of a disclosure header. Worktree cards take the accent of their state — failures read as danger,
 * cancelled work stays neutral — while agent tools keep the shared outline.
 */
@Composable
internal fun toolOutline(call: HbToolCall): Color = when {
    !call.isWorktree || call.status == HbToolStatus.Cancelled -> HbTheme.surfaces.outline
    call.status == HbToolStatus.Error -> tonePalette(HbTone.Danger).accent
    else -> HbTheme.surfaces.accent
}

/**
 * Line limit of disclosure copy. Worktree cards carry instructions the user acts on, so their title and
 * summary wrap in full; agent tools keep [toolLines] quiet lines.
 */
internal fun toolTextLines(call: HbToolCall, toolLines: Int): Int = if (call.isWorktree) Int.MAX_VALUE else toolLines
