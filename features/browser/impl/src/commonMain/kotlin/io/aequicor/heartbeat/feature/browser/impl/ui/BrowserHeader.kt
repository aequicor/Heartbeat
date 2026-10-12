package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbMenuButton
import io.aequicor.heartbeat.ds.components.HbMenuItem
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbWindowDragArea
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.LocalPaneExpansionAvailable
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenIntent
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenState
import io.aequicor.heartbeat.feature.browser.impl.resources.Res
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_actions
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_clear_data
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_expand
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_hide
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_panel
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_title
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/** A single caption-safe row; compact panes put secondary actions in a menu to leave room for tabs. */
@Composable
internal fun BrowserHeader(
    state: BrowserScreenState,
    onIntent: (BrowserScreenIntent) -> Unit,
    onClearData: () -> Unit,
) {
    HbWindowDragArea(
        Modifier.fillMaxWidth().height(HbTheme.dimensions.headerHeight)
            .background(HbTheme.surfaces.sidebar).testTag("browser-header"),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.xs)) {
            val isCompact = maxWidth < HbTheme.dimensions.composerToolbarBreakpoint
            HbRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
                if (!state.hasTabs || !isCompact) {
                    HbText(
                        stringResource(Res.string.browser_title),
                        Modifier.semantics { heading() },
                        style = HbTheme.typography.title,
                        maxLines = 1,
                    )
                }
                if (state.hasTabs) {
                    BrowserTabStrip(state, onIntent, Modifier.weight(1f))
                    BrowserHeaderActions(state, isCompact, onIntent, onClearData)
                } else {
                    Spacer(Modifier.weight(1f))
                }
                HbIconButton(
                    HbIcons.Close,
                    stringResource(Res.string.browser_hide),
                    { onIntent(BrowserScreenIntent.Hide) },
                    Modifier.testTag("browser-hide"),
                )
            }
        }
    }
}

@Composable
private fun BrowserHeaderActions(
    state: BrowserScreenState,
    isCompact: Boolean,
    onIntent: (BrowserScreenIntent) -> Unit,
    onClearData: () -> Unit,
) {
    val isExpansionAvailable = LocalPaneExpansionAvailable.current
    val expandLabel = stringResource(if (state.isExpanded) Res.string.browser_panel else Res.string.browser_expand)
    val expandIcon = if (state.isExpanded) HbIcons.Minimize else HbIcons.Maximize
    val clearLabel = stringResource(Res.string.browser_clear_data)
    if (isCompact) {
        var isMenuExpanded by remember { mutableStateOf(false) }
        HbMenuButton(
            icon = HbIcons.More,
            contentDescription = stringResource(Res.string.browser_actions),
            items = buildList {
                if (isExpansionAvailable) add(HbMenuItem("expand", expandLabel, expandIcon))
                add(HbMenuItem("clear", clearLabel, HbIcons.Trash, isFocusRestoredOnSelect = false))
            }.toImmutableList(),
            isExpanded = isMenuExpanded,
            onExpandedChange = { isMenuExpanded = it },
            onItem = {
                if (it == "clear") onClearData() else onIntent(BrowserScreenIntent.ToggleExpanded)
            },
            modifier = Modifier.testTag("browser-actions"),
        )
    } else {
        if (isExpansionAvailable) {
            HbIconButton(expandIcon, expandLabel, { onIntent(BrowserScreenIntent.ToggleExpanded) })
        }
        HbIconButton(HbIcons.Trash, clearLabel, onClearData, Modifier.testTag("browser-clear-data"))
    }
}
