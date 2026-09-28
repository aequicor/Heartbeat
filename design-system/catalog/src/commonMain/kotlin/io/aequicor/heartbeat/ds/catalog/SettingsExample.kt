package io.aequicor.heartbeat.ds.catalog

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.resources.HbString
import io.aequicor.heartbeat.ds.resources.hbString
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Every button style and size with its disabled state, settings rows (switch, navigation, long Russian text that
 * fades instead of ending with an ellipsis), the error banner, loading and empty states and the modal dialog.
 * Hover, pressed and keyboard focus are exercised directly in the catalog.
 */
@Composable
internal fun SettingsExample(modifier: Modifier = Modifier) {
    var isPreferred by rememberSaveable { mutableStateOf(true) }
    var isDialogOpen by rememberSaveable { mutableStateOf(false) }
    HbColumn(modifier.fillMaxWidth().testTag("settings-example"), gap = HbTheme.spacing.l) {
        HbText(hbString(HbString.SettingsCatalog), style = HbTheme.typography.title)
        HbButtonSize.entries.forEach { size ->
            HbFlowRow(gap = HbTheme.spacing.s) {
                HbButtonStyle.entries.forEach { style ->
                    HbButton(style.name, {}, style = style, size = size)
                }
                HbButton(hbString(HbString.Disabled), {}, style = HbButtonStyle.Secondary, enabled = false, size = size)
            }
        }
        HbSettingsSection(hbString(HbString.SettingProvider), description = hbString(HbString.SettingProviderHint)) {
            HbSettingsRow(
                hbString(HbString.SettingPreferEngine),
                description = hbString(HbString.SettingPreferEngineHint),
            ) {
                HbSwitch(isPreferred, { isPreferred = it }, hbString(HbString.SettingPreferEngine))
            }
            HbDivider()
            HbSettingsRow(
                hbString(HbString.OpenDialog),
                onClick = { isDialogOpen = true },
                modifier = Modifier.testTag("settings-example-dialog"),
            ) {
                HbIcon(HbIcons.ChevronRight, null, tint = HbTheme.colors.textSecondary)
            }
            HbDivider()
            HbSettingsRow(hbString(HbString.Disabled), enabled = false) {
                HbSwitch(false, {}, hbString(HbString.Disabled), enabled = false)
            }
        }
        HbBanner(hbString(HbString.ErrorBanner)) {
            HbButton(hbString(HbString.RetryAction), {}, style = HbButtonStyle.Secondary, size = HbButtonSize.Small)
        }
        HbLoadingState(hbString(HbString.LoadingLabel))
        HbEmptyState(hbString(HbString.EmptyTitle), description = hbString(HbString.EmptyHint))
    }
    if (isDialogOpen) {
        HbDialog(
            hbString(HbString.DialogTitle),
            onDismissRequest = { isDialogOpen = false },
            actions = {
                HbButton(hbString(HbString.CancelAction), { isDialogOpen = false }, style = HbButtonStyle.Ghost)
                HbButton(hbString(HbString.DangerAction), { isDialogOpen = false }, style = HbButtonStyle.Danger)
            },
        ) {
            HbText(hbString(HbString.DialogBody), color = HbTheme.colors.textSecondary)
        }
    }
}
