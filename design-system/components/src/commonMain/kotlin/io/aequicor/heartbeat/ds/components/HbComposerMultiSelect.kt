package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.collections.immutable.ImmutableList

private val log = Log.tag("DS/ComposerMultiSelect")

/** Localized text for a searchable selector; [accessibleLabel] is semantic only, never a visible heading. */
@Immutable
public data class HbMultiSelectLabels(
    val accessibleLabel: String,
    val search: String,
    val clearSearch: String,
    val empty: String,
    val selected: String,
    val clear: String,
    val done: String,
)

/**
 * Controlled multiple selection that stays open while choices change. One choice uses the same checkboxes as many.
 * The flat Foundation implementation is shared by all kits; host tokens provide desktop/touch sizing, and the
 * platform popup owns input interception. Wide windows use an anchored popup, compact windows a bottom sheet.
 * Selection and expansion belong to the caller; only the transient search and keyboard focus live here.
 * [isLabelVisible] keeps an icon-only toolbar trigger when another control already names the selection; its
 * accessible label and the full selection popup remain unchanged.
 */
@Composable
public fun HbComposerMultiSelect(
    label: String,
    actions: ImmutableList<HbComposerAction>,
    labels: HbMultiSelectLabels,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAction: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    isLabelVisible: Boolean = true,
) {
    require(actions.map { it.id }.distinct().size == actions.size) { "Selection ids must be unique" }
    val focus = remember { FocusRequester() }
    var hasOpened by remember { mutableStateOf(false) }
    val isOpen = isExpanded && enabled && actions.isNotEmpty()
    SideEffect(isOpen) {
        if (hasOpened && !isOpen) focus.requestFocus()
        hasOpened = isOpen
    }
    val expand: (Boolean) -> Unit = {
        log.i { "multiple selection expanded=$it" }
        onExpandedChange(it)
    }
    Box(modifier) {
        HbTooltip(labels.accessibleLabel) {
            ComposerMenuPill(
                label = label,
                onClick = { expand(!isOpen) },
                modifier = Modifier.focusRequester(focus)
                    .semantics { contentDescription = labels.accessibleLabel }
                    .onPreviewKeyEvent {
                        val isOpeningEnabled = enabled && actions.isNotEmpty()
                        val isArrow = it.key == Key.DirectionDown || it.key == Key.DirectionUp
                        if (isOpeningEnabled && it.type == KeyEventType.KeyDown && isArrow) {
                            expand(true)
                            true
                        } else {
                            false
                        }
                    },
                icon = icon,
                enabled = enabled && actions.isNotEmpty(),
                isAccent = actions.any { it.isSelected },
                isLabelVisible = isLabelVisible,
            )
        }
        if (isOpen) {
            ComposerMultiSelectPopup(
                actions,
                labels,
                onDismiss = { expand(false) },
                onAction = {
                    log.i { "multiple selection toggled" }
                    onAction(it)
                },
                onClear = {
                    log.i { "multiple selection cleared" }
                    onClear()
                },
            )
        }
    }
}
