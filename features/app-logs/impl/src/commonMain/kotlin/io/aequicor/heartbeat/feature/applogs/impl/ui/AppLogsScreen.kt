package io.aequicor.heartbeat.feature.applogs.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.applogs.impl.presentation.AppLogsActionUi
import io.aequicor.heartbeat.feature.applogs.impl.presentation.AppLogsNoticeUi
import io.aequicor.heartbeat.feature.applogs.impl.presentation.AppLogsScreenIntent
import io.aequicor.heartbeat.feature.applogs.impl.presentation.AppLogsScreenState
import io.aequicor.heartbeat.feature.applogs.impl.resources.Res
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_archive
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_archive_description
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_back
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_cancelled
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_copied
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_copy
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_copy_description
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_description
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_disabled
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_empty
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_error
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_folder
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_folder_description
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_opened
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_recent
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_retained
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_retry
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_save
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_save_description
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_saved
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_title
import io.aequicor.heartbeat.feature.applogs.impl.resources.logs_working
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Compact, keyboard-accessible log actions inside the settings host or a standalone navigation entry. */
@Composable
internal fun AppLogsContent(
    state: AppLogsScreenState,
    onIntent: (AppLogsScreenIntent) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    HbColumn(
        modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).testTag("app-logs"),
        gap = HbTheme.spacing.none,
    ) {
        if (onBack != null) {
            HbPaneHeader(
                stringResource(Res.string.logs_title),
                navigation = { HbIconButton(HbIcons.ArrowLeft, stringResource(Res.string.logs_back), onBack) },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HbLazyColumn(
                Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth).fillMaxSize().testTag("logs-list"),
                gap = HbTheme.spacing.xl,
                contentPadding = PaddingValues(HbTheme.spacing.xl),
            ) {
                item(key = "description") {
                    HbText(
                        stringResource(Res.string.logs_description),
                        style = HbTheme.typography.caption,
                        color = HbTheme.colors.textSecondary,
                    )
                }
                item(key = "recent") {
                    HbSettingsSection(stringResource(Res.string.logs_recent)) {
                        LogActionRow(AppLogsActionUi.CopyRecent, !state.isBusy, onIntent)
                        HbDivider()
                        LogActionRow(AppLogsActionUi.SaveRecent, !state.isBusy, onIntent)
                    }
                }
                item(key = "retained") {
                    HbSettingsSection(stringResource(Res.string.logs_retained)) {
                        LogActionRow(AppLogsActionUi.SaveArchive, !state.isBusy, onIntent)
                        HbDivider()
                        LogActionRow(AppLogsActionUi.OpenFolder, !state.isBusy, onIntent)
                    }
                }
                if (state.isBusy) {
                    item(key = "progress") { HbLoadingState(stringResource(Res.string.logs_working)) }
                }
                state.notice?.let { notice ->
                    item(key = "notice") { LogsNotice(notice, onIntent) }
                }
            }
        }
    }
}

@Composable
private fun LogActionRow(
    action: AppLogsActionUi,
    isEnabled: Boolean,
    onIntent: (AppLogsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = when (action) {
        AppLogsActionUi.CopyRecent -> Res.string.logs_copy
        AppLogsActionUi.SaveRecent -> Res.string.logs_save
        AppLogsActionUi.SaveArchive -> Res.string.logs_archive
        AppLogsActionUi.OpenFolder -> Res.string.logs_folder
    }
    val description = when (action) {
        AppLogsActionUi.CopyRecent -> Res.string.logs_copy_description
        AppLogsActionUi.SaveRecent -> Res.string.logs_save_description
        AppLogsActionUi.SaveArchive -> Res.string.logs_archive_description
        AppLogsActionUi.OpenFolder -> Res.string.logs_folder_description
    }
    HbSettingsRow(
        stringResource(title),
        modifier.testTag("logs-action-$action"),
        description = stringResource(description),
        onClick = { onIntent(AppLogsScreenIntent.Execute(action)) },
        enabled = isEnabled,
    )
}

@Composable
private fun LogsNotice(
    notice: AppLogsNoticeUi,
    onIntent: (AppLogsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val announcement = modifier.semantics { liveRegion = LiveRegionMode.Polite }
    if (notice == AppLogsNoticeUi.Failure) {
        HbBanner(stringResource(Res.string.logs_error), announcement.testTag("logs-error")) {
            HbButton(
                stringResource(Res.string.logs_retry),
                { onIntent(AppLogsScreenIntent.Retry) },
                Modifier.testTag("logs-retry"),
                style = HbButtonStyle.Secondary,
            )
        }
    } else {
        HbText(
            stringResource(notice.message()),
            announcement.testTag("logs-notice"),
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
    }
}

private fun AppLogsNoticeUi.message(): StringResource = when (this) {
    AppLogsNoticeUi.Copied -> Res.string.logs_copied
    AppLogsNoticeUi.Saved -> Res.string.logs_saved
    AppLogsNoticeUi.Opened -> Res.string.logs_opened
    AppLogsNoticeUi.Cancelled -> Res.string.logs_cancelled
    AppLogsNoticeUi.Empty -> Res.string.logs_empty
    AppLogsNoticeUi.Failure -> Res.string.logs_error
    AppLogsNoticeUi.Disabled -> Res.string.logs_disabled
}

@Preview
@Composable
private fun AppLogsLightPreview() {
    HbTheme(darkTheme = false) { AppLogsContent(AppLogsScreenState(), {}, null) }
}

@Preview
@Composable
private fun AppLogsDarkPreview() {
    HbTheme(darkTheme = true) { AppLogsContent(AppLogsScreenState(notice = AppLogsNoticeUi.Failure), {}, null) }
}
