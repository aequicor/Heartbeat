package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme

private val unifiedToolLog = Log.tag("DS/UnifiedTool")

/** Quiet nested disclosure; reasoning is labelled as content and never presented as a completed tool. */
@Composable
internal fun HbUnifiedToolHeader(
    call: HbToolCall,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    labels: HbToolLabels = HbToolLabels(),
) {
    val interactions = remember { MutableInteractionSource() }
    val isFocused by interactions.collectIsFocusedAsState()
    val isHovered by interactions.collectIsHoveredAsState()
    val isPressed by interactions.collectIsPressedAsState()
    val background = when {
        isPressed -> HbTheme.colors.pressedOverlay.compositeOver(HbTheme.surfaces.tool)
        isHovered -> HbTheme.colors.interactionHoverOverlay.compositeOver(HbTheme.surfaces.tool)
        else -> HbTheme.surfaces.tool
    }
    val radius = HbTheme.dimensions.toolPadding
    val bottomRadius = if (isExpanded) HbTheme.spacing.none else radius
    val shape = RoundedCornerShape(radius, radius, bottomRadius, bottomRadius)
    HbRow(
        modifier.fillMaxWidth().heightIn(min = HbTheme.dimensions.touchTarget)
            .hbFocusOutline(isFocused, shape)
            .background(background, shape)
            .border(HbTheme.dimensions.borderWidth, HbTheme.surfaces.outline, shape)
            .clickable(interactions, indication = null, role = Role.Button) {
                unifiedToolLog.i { "disclosure changed id=${call.id} expanded=${!isExpanded}" }
                onExpandedChange(!isExpanded)
            }
            .semantics { stateDescription = if (isExpanded) labels.collapse else labels.expand }
            .padding(HbTheme.dimensions.toolPadding),
        gap = HbTheme.spacing.l,
    ) {
        UnifiedToolIcon(call)
        UnifiedToolTitle(call, Modifier.weight(1f))
        UnifiedToolStatus(call, labels)
        HbIcon(if (isExpanded) HbIcons.ChevronDown else HbIcons.ChevronRight, contentDescription = null)
    }
}

@Composable
private fun UnifiedToolIcon(call: HbToolCall) {
    val isReasoning = call.kind == HbToolKind.Reasoning
    HbIcon(
        if (isReasoning) HbIcons.Sparkles else toolStatusIcon(call.status),
        contentDescription = null,
        modifier = Modifier.size(HbTheme.dimensions.iconSize),
        tint = when {
            isReasoning -> HbTheme.surfaces.accent
            call.status == HbToolStatus.Complete -> HbTheme.surfaces.success
            else -> HbTheme.colors.textSecondary
        },
    )
}

@Composable
private fun UnifiedToolTitle(call: HbToolCall, modifier: Modifier = Modifier) {
    HbColumn(modifier, gap = HbTheme.spacing.xxs) {
        HbText(call.title, style = HbTheme.typography.label, maxLines = 1)
        if (call.summary.isNotBlank()) {
            HbText(
                call.summary,
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun UnifiedToolStatus(call: HbToolCall, labels: HbToolLabels) {
    if (call.kind == HbToolKind.Reasoning) return
    val tint = if (call.status == HbToolStatus.Complete) HbTheme.surfaces.success else HbTheme.colors.textSecondary
    HbText(
        toolStatusLabel(call.status, labels),
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = HbTheme.typography.caption,
        color = tint,
        maxLines = 1,
    )
}

private fun toolStatusIcon(status: HbToolStatus) = when (status) {
    HbToolStatus.Complete -> HbIcons.Success
    HbToolStatus.Pending, HbToolStatus.Running -> HbIcons.More
    HbToolStatus.Error -> HbIcons.Alert
    HbToolStatus.Cancelled -> HbIcons.Close
}

private fun toolStatusLabel(status: HbToolStatus, labels: HbToolLabels): String = when (status) {
    HbToolStatus.Pending -> labels.pending
    HbToolStatus.Running -> labels.running
    HbToolStatus.Complete -> labels.complete
    HbToolStatus.Error -> labels.error
    HbToolStatus.Cancelled -> labels.cancelled
}
