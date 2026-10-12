package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbNavigationItem
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.LocalPaneExpansionAvailable
import io.aequicor.heartbeat.ds.layouts.hbHorizontalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenIntent
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenState
import io.aequicor.heartbeat.feature.browser.impl.resources.Res
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_approve_control
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_clear_data
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_close_tab
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_expand
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_hide
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_new_tab
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_panel
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_reject_control
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_return_agent
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_take_control
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_title
import org.jetbrains.compose.resources.stringResource

/** Caption-safe fixed controls stay reachable even with many tabs. */
@Composable
internal fun BrowserTabs(state: BrowserScreenState, onIntent: (BrowserScreenIntent) -> Unit) {
    // Compact windows already use the whole workspace, so a panel/expand toggle has no effect.
    val isPresentationSwitchAvailable = LocalPaneExpansionAvailable.current
    HbPaneHeader(
        stringResource(Res.string.browser_title),
        actions = {
            if (state.hasTabs && isPresentationSwitchAvailable) {
                HbButton(
                    stringResource(
                        if (state.isExpanded) {
                            Res.string.browser_panel
                        } else {
                            Res.string.browser_expand
                        },
                    ),
                    { onIntent(BrowserScreenIntent.ToggleExpanded) },
                    style = HbButtonStyle.Ghost,
                )
            }
            if (state.hasTabs) {
                HbIconButton(
                    HbIcons.Trash,
                    stringResource(Res.string.browser_clear_data),
                    { onIntent(BrowserScreenIntent.ClearData) },
                )
            }
            HbIconButton(
                HbIcons.Close,
                stringResource(Res.string.browser_hide),
                { onIntent(BrowserScreenIntent.Hide) },
            )
        },
    )
    if (!state.hasTabs) return
    HbRow(
        Modifier.fillMaxWidth().selectableGroup().hbHorizontalScroll(rememberScrollState()),
        gap = HbTheme.spacing.xs,
    ) {
        state.tabs.forEach { tab ->
            key(tab.id) {
                HbNavigationItem(
                    label = tab.title.ifBlank { stringResource(Res.string.browser_new_tab) },
                    onClick = { onIntent(BrowserScreenIntent.SelectTab(tab.id)) },
                    modifier = Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth / 2),
                    isSelected = state.selectedTabId == tab.id,
                    role = Role.Tab,
                )
                HbIconButton(
                    HbIcons.Close,
                    stringResource(Res.string.browser_close_tab),
                    { onIntent(BrowserScreenIntent.CloseTab(tab.id)) },
                )
            }
        }
        HbButton(
            stringResource(Res.string.browser_new_tab),
            { onIntent(BrowserScreenIntent.NewTab) },
            style = HbButtonStyle.Ghost,
        )
    }
    BrowserControl(state, onIntent)
}

@Composable
private fun BrowserControl(state: BrowserScreenState, onIntent: (BrowserScreenIntent) -> Unit) {
    val tab = state.tabs.firstOrNull { it.id == state.selectedTabId }
    val requestId = tab?.controlRequestId
    if (tab != null && requestId != null) {
        HbFlowRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
            HbButton(
                stringResource(Res.string.browser_approve_control),
                { onIntent(BrowserScreenIntent.ApproveControl(tab.id, requestId)) },
            )
            HbButton(
                stringResource(Res.string.browser_reject_control),
                { onIntent(BrowserScreenIntent.RejectControl(tab.id, requestId)) },
                style = HbButtonStyle.Ghost,
            )
        }
    }
    if (tab?.hasAgent == true) {
        HbButton(
            stringResource(
                if (tab.isHumanControlled) {
                    Res.string.browser_return_agent
                } else {
                    Res.string.browser_take_control
                },
            ),
            { onIntent(BrowserScreenIntent.ToggleControl) },
            style = HbButtonStyle.Secondary,
        )
    }
}
