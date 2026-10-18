package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import io.aequicor.heartbeat.ds.components.HbActivityIndicator
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbMenuButton
import io.aequicor.heartbeat.ds.components.HbMenuItem
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app.AppQuickActionsUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.QuickActionUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PlaceIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.Res
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_busy
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_dismiss
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_quick_actions_more
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/** Test tag of the row of quick actions. */
internal const val QUICK_ACTIONS_TAG = "quick-actions"

/**
 * The quick actions of the canonical app, one row above the chat input, and the notice of the last failure above
 * them. Actions that do not fit the width move into a menu in their declared order. Pressing one asks its
 * confirmation first; the press goes to the owner's methods through the app, never to the chat input. Open
 * confirmations and menus are revoked when the execution authority changes.
 */
@Composable
internal fun QuickActionsView(
    state: AppQuickActionsUi,
    onIntent: (PlaceIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.isEmpty) return
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
        state.notice?.let { notice ->
            HbBanner(
                message = notice.text(),
                tone = notice.tone(),
                actions = {
                    HbButton(
                        stringResource(Res.string.service_ui_dismiss),
                        { onIntent(PlaceIntent.DismissQuickNotice(state.authority)) },
                        style = HbButtonStyle.Ghost,
                        size = HbButtonSize.Small,
                    )
                },
            )
        }
        if (state.actions.isNotEmpty()) QuickActionRow(state, onIntent)
    }
}

@Composable
private fun QuickActionRow(state: AppQuickActionsUi, onIntent: (PlaceIntent) -> Unit) {
    var confirming by remember(state.authority) { mutableStateOf<String?>(null) }
    var isMenuOpen by remember(state.authority) { mutableStateOf(false) }
    val actions = state.actions
    val run = { action: QuickActionUi -> onIntent(PlaceIntent.RunQuickAction(action, state.authority)) }
    val press = { action: QuickActionUi -> if (action.confirm == null) run(action) else confirming = action.id }
    val moreLabel = stringResource(Res.string.service_ui_quick_actions_more)
    val busyLabel = stringResource(Res.string.service_ui_busy)
    OverflowRow(
        count = actions.size,
        gap = HbTheme.spacing.s,
        item = { index -> QuickActionButton(actions[index], state.isAvailable, press) },
        overflow = { hidden ->
            HbMenuButton(
                icon = HbIcons.More,
                contentDescription = moreLabel,
                items = hidden.map { actions[it].menuItem(state.isAvailable, busyLabel) }.toImmutableList(),
                isExpanded = isMenuOpen,
                onExpandedChange = { isMenuOpen = it },
                onItem = { id -> actions.firstOrNull { it.id == id }?.let(press) },
            )
        },
        modifier = Modifier.fillMaxWidth().testTag(QUICK_ACTIONS_TAG),
    )
    val pending = actions.firstOrNull { it.id == confirming }
    val question = pending?.confirm
    if (pending != null && question != null) {
        ConfirmActionDialog(
            label = pending.label,
            question = question,
            style = pending.style,
            onDismiss = { confirming = null },
            onConfirm = {
                confirming = null
                run(pending)
            },
        )
    }
}

/** A button of the row, or an icon button named by the label when the spec gives a known icon. */
@Composable
private fun QuickActionButton(action: QuickActionUi, isAvailable: Boolean, onPress: (QuickActionUi) -> Unit) {
    val icon = iconOf(action.icon)
    val isEnabled = action.isEnabled && isAvailable && !action.isBusy
    HbRow(gap = HbTheme.spacing.s) {
        if (icon == null) {
            HbButton(
                action.label,
                { onPress(action) },
                style = action.style.hb(),
                enabled = isEnabled,
                size = HbButtonSize.Small,
            )
        } else {
            HbIconButton(icon, action.label, { onPress(action) }, enabled = isEnabled)
        }
        if (action.isBusy) HbActivityIndicator(contentDescription = stringResource(Res.string.service_ui_busy))
    }
}

private fun QuickActionUi.menuItem(isAvailable: Boolean, busyLabel: String): HbMenuItem = HbMenuItem(
    id = id,
    label = label,
    icon = iconOf(icon),
    isEnabled = isEnabled && isAvailable && !isBusy,
    badge = busyLabel.takeIf { isBusy },
)

/**
 * [count] items from [item] side by side; the ones that do not fit [overflow] into one trailing control for
 * the hidden indices. Items are measured in semantics-free probes first, so only the shown ones exist for assistive
 * technology and keyboard focus.
 */
// Each slot is composed twice on purpose: once only to be measured, once to be shown.
@Suppress("ContentSlotReused")
@Composable
private fun OverflowRow(
    count: Int,
    gap: Dp,
    item: @Composable (index: Int) -> Unit,
    overflow: @Composable (hidden: List<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    SubcomposeLayout(modifier) { constraints ->
        val spacing = gap.roundToPx()
        val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        val widths = List(count) { index ->
            subcompose(Probe(index)) { Hidden { item(index) } }.sumOf { it.measure(loose).width }
        }
        val shown = fittingCount(widths, spacing, constraints.maxWidth) {
            subcompose(OverflowProbe) { Hidden { overflow(emptyList()) } }.sumOf { it.measure(loose).width }
        }
        val items = List(shown) { index -> subcompose(Shown(index)) { item(index) }.map { it.measure(loose) } }
        val hidden = (shown until count).toList()
        val more = if (hidden.isEmpty()) {
            emptyList()
        } else {
            subcompose(More) { overflow(hidden) }.map { it.measure(loose) }
        }
        val placed = items.flatten() + more
        val height = placed.maxOfOrNull { it.height } ?: 0
        val width = placed.sumOf { it.width } + spacing * (placed.size - 1).coerceAtLeast(0)
        layout(constraints.constrainWidth(width), constraints.constrainHeight(height)) {
            var x = 0
            for (placeable in placed) {
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width + spacing
            }
        }
    }
}

/**
 * How many leading items of [widths] fit in [available] with [spacing] between them; when not all do, room for the
 * overflow control is kept, whose width [overflowWidth] measures.
 */
private fun fittingCount(widths: List<Int>, spacing: Int, available: Int, overflowWidth: () -> Int): Int {
    fun used(count: Int): Int = widths.take(count).sum() + spacing * (count - 1).coerceAtLeast(0)
    if (used(widths.size) <= available) return widths.size
    val more = overflowWidth()
    return (widths.size downTo 1).firstOrNull { used(it) + spacing + more <= available } ?: 0
}

/** Content that is only measured: invisible to semantics and focus. */
@Composable
private fun Hidden(content: @Composable () -> Unit) {
    Box(Modifier.clearAndSetSemantics {}.focusProperties { canFocus = false }) { content() }
}

private data class Probe(val index: Int)

private data class Shown(val index: Int)

private data object OverflowProbe

private data object More
