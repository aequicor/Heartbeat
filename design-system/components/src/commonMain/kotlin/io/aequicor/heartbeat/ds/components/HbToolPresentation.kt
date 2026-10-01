package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.aequicor.heartbeat.ds.theme.HbTheme

/** Whether [this] reports host-owned checkout or build lifecycle rather than an agent call. */
internal val HbToolCall.isWorktree: Boolean get() = kind == HbToolKind.Worktree

/**
 * Whether the header offers a disclosure. A worktree card without blocks has nothing to reveal, so it never
 * pretends to expand; agent tools keep theirs while output may still arrive.
 */
internal val HbToolCall.isExpandable: Boolean get() = !isWorktree || blocks.isNotEmpty()

/**
 * Click target of a disclosure row of [call]: a button that announces [isExpanded] and calls [onToggle].
 * A call without a disclosure ([isExpandable]) becomes one static node that still reads its copy and status.
 */
@Composable
internal fun Modifier.toolDisclosure(
    call: HbToolCall,
    isExpanded: Boolean,
    labels: HbToolLabels,
    interactions: MutableInteractionSource,
    focusShape: Shape,
    onToggle: () -> Unit,
): Modifier {
    val isFocused by interactions.collectIsFocusedAsState()
    if (!call.isExpandable) return semantics(mergeDescendants = true) {}
    val label = if (isExpanded) labels.collapse else labels.expand
    return hbFocusOutline(isFocused, focusShape)
        .clickable(interactions, indication = null, role = Role.Button, onClickLabel = label, onClick = onToggle)
        .semantics { stateDescription = label }
}

/** Test tag of the disclosure header of [call]. */
internal fun toolHeaderTag(call: HbToolCall): String = "tool:${call.id}"

/** Test tag of [action] of [call]; action ids are unique only within their call. */
internal fun toolActionTag(call: HbToolCall, action: HbToolAction): String = "tool-action:${call.id}:${action.id}"

/** Test tag of every content row of the block [blockId]. */
internal fun toolBlockTag(blockId: String): String = "tool-block:$blockId"

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
