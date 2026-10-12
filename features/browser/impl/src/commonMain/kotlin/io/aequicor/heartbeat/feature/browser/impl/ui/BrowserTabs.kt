package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenIntent
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenState
import io.aequicor.heartbeat.feature.browser.impl.resources.Res
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_approve_control
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_cancel
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_clear_data_confirm
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_clear_data_message
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_clear_data_title
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_reject_control
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_return_agent
import org.jetbrains.compose.resources.stringResource

/** Caption-safe fixed controls stay reachable even with many tabs. */
@Composable
internal fun BrowserTabs(state: BrowserScreenState, onIntent: (BrowserScreenIntent) -> Unit) {
    // Clearing deletes the persistent profile's cookies and sign-ins, so it always asks first.
    var isClearDataConfirmationShown by rememberSaveable { mutableStateOf(false) }
    BrowserHeader(state, onIntent, onClearData = { isClearDataConfirmationShown = true })
    if (isClearDataConfirmationShown) {
        ClearDataConfirmation(
            onConfirm = {
                isClearDataConfirmationShown = false
                onIntent(BrowserScreenIntent.ClearData)
            },
            onDismiss = { isClearDataConfirmationShown = false },
        )
    }
    if (!state.hasTabs) return
    BrowserControl(state, onIntent)
}

@Composable
private fun ClearDataConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    HbDialog(
        stringResource(Res.string.browser_clear_data_title),
        onDismiss,
        Modifier.testTag("browser-clear-data-dialog"),
        actions = {
            HbButton(
                stringResource(Res.string.browser_cancel),
                onDismiss,
                Modifier.testTag("browser-clear-data-cancel"),
                style = HbButtonStyle.Ghost,
            )
            HbButton(
                stringResource(Res.string.browser_clear_data_confirm),
                onConfirm,
                Modifier.testTag("browser-clear-data-confirm"),
                style = HbButtonStyle.Danger,
            )
        },
    ) {
        HbText(stringResource(Res.string.browser_clear_data_message))
    }
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
    if (tab?.hasAgent == true && tab.isHumanControlled) {
        HbButton(
            stringResource(Res.string.browser_return_agent),
            { onIntent(BrowserScreenIntent.ToggleControl) },
            style = HbButtonStyle.Ghost,
        )
    }
}
