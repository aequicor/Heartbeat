package io.aequicor.heartbeat.ds.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlinx.collections.immutable.ImmutableList

private val log = Log.tag("DS/ToolCall")

/**
 * Expandable tool result in the parent's vertical flow. Supply [isExpanded] to control state,
 * or keep it null to save disclosure by stable tool identity through lazy disposal and restoration.
 * Transcripts should place [HbToolCallHeader] and prepared [HbToolPayloadRow]s in their outer lazy list.
 */
@Composable
public fun HbToolCallView(
    toolCall: HbToolCall,
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: (Boolean) -> Unit = {},
    labels: HbToolLabels = HbToolLabels(),
    onLinkClick: ((String) -> Unit)? = null,
) {
    var isLocallyExpanded by rememberSaveable(toolCall.id) { mutableStateOf(false) }
    val isOpen = isExpanded ?: isLocallyExpanded
    HbColumn(
        modifier = modifier.fillMaxWidth(),
        gap = HbTheme.spacing.s,
    ) {
        HbToolCallHeader(
            toolCall = toolCall,
            isExpanded = isOpen,
            labels = labels,
            onExpandedChange = { isNextExpanded ->
                if (isExpanded == null) {
                    log.d { "local disclosure stored id=${toolCall.id} expanded=$isNextExpanded" }
                    isLocallyExpanded = isNextExpanded
                }
                onExpandedChange(isNextExpanded)
            },
        )
        if (isOpen) ToolPayload(toolCall.blocks, labels, onLinkClick)
    }
}

/** Shared disclosure row and summary; the owner retains expanded state and positions payload rows. */
@Composable
internal fun HbToolCallHeader(
    toolCall: HbToolCall,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    labels: HbToolLabels = HbToolLabels(),
    isUnified: Boolean = false,
) {
    if (isUnified) {
        HbUnifiedToolHeader(toolCall, isExpanded, onExpandedChange, modifier, labels)
        return
    }
    HbColumn(modifier = modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
        ToolHeaderButton(
            toolCall = toolCall,
            isExpanded = isExpanded,
            labels = labels,
            onClick = {
                val isNextExpanded = !isExpanded
                log.i { "tool disclosure changed id=${toolCall.id} expanded=$isNextExpanded" }
                onExpandedChange(isNextExpanded)
            },
        )
        if (toolCall.summary.isNotBlank()) {
            HbText(
                text = toolCall.summary,
                modifier = Modifier.padding(horizontal = HbTheme.spacing.xs),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun ToolHeaderButton(toolCall: HbToolCall, isExpanded: Boolean, labels: HbToolLabels, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val background = toolHeaderBackground(isHovered, isPressed)
    val status = when (toolCall.status) {
        HbToolStatus.Pending -> labels.pending
        HbToolStatus.Cancelled -> labels.cancelled
        HbToolStatus.Running -> labels.running
        HbToolStatus.Complete -> labels.complete
        HbToolStatus.Error -> labels.error
    }
    HbRow(
        modifier = Modifier.fillMaxWidth().heightIn(min = HbTheme.dimensions.touchTarget)
            .hbFocusOutline(isFocused, HbTheme.shapes.small)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = if (isExpanded) labels.collapse else labels.expand,
                onClick = onClick,
            )
            .background(background, HbTheme.shapes.small)
            .semantics { stateDescription = if (isExpanded) labels.collapse else labels.expand }
            .padding(horizontal = HbTheme.spacing.m, vertical = HbTheme.spacing.xxs),
        gap = HbTheme.spacing.s,
    ) {
        HbIcon(
            icon = if (isExpanded) HbIcons.ChevronDown else HbIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(HbTheme.dimensions.iconSmallSize),
        )
        HbText(
            text = toolCall.title,
            modifier = Modifier.weight(1f),
            style = HbTheme.typography.label,
            maxLines = 1,
        )
        HbRow(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            gap = HbTheme.spacing.xs,
        ) {
            HbIcon(
                icon = when (toolCall.status) {
                    HbToolStatus.Pending, HbToolStatus.Running -> HbIcons.More
                    HbToolStatus.Cancelled -> HbIcons.Close
                    HbToolStatus.Complete -> HbIcons.Check
                    HbToolStatus.Error -> HbIcons.Alert
                },
                contentDescription = null,
                modifier = Modifier.size(HbTheme.dimensions.iconSmallSize),
                tint = HbTheme.colors.textPrimary,
            )
            HbText(text = status, style = HbTheme.typography.label, maxLines = 1)
        }
    }
}

@Composable
private fun toolHeaderBackground(isHovered: Boolean, isPressed: Boolean): Color {
    val colors = HbTheme.colors
    val target = when {
        isPressed -> colors.pressedOverlay.compositeOver(colors.surfaceElevated)
        isHovered -> colors.interactionHoverOverlay.compositeOver(colors.surfaceElevated)
        else -> colors.surfaceElevated
    }
    val motion = HbTheme.motion
    return key(colors) {
        animateColorAsState(
            targetValue = target,
            animationSpec = if (motion.isReducedMotion) snap() else tween(motion.fastMillis),
            label = "toolHeaderSurface",
        ).value
    }
}

@Composable
private fun ToolPayload(
    blocks: ImmutableList<HbToolBlock>,
    labels: HbToolLabels,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val rows = remember(blocks) { prepareToolRows(blocks) }
    HbColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.xs),
        gap = HbTheme.elevation.none,
    ) {
        rows.forEachIndexed { index, row ->
            val isContinuation = row.console?.isFirst == false || row.diff?.isFirst == false
            val top = if (index == 0 || isContinuation) HbTheme.elevation.none else HbTheme.spacing.s
            key(row.id) {
                HbToolPayloadRow(
                    row = row,
                    modifier = Modifier.padding(top = top),
                    labels = labels,
                    onLinkClick = onLinkClick,
                )
            }
        }
    }
}
