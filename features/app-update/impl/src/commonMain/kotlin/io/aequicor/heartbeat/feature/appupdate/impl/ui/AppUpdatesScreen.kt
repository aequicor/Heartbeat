package io.aequicor.heartbeat.feature.appupdate.impl.ui

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
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbProgressBar
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.appupdate.impl.presentation.AppBuildUi
import io.aequicor.heartbeat.feature.appupdate.impl.presentation.AppUpdatesScreenIntent
import io.aequicor.heartbeat.feature.appupdate.impl.presentation.AppUpdatesScreenState
import io.aequicor.heartbeat.feature.appupdate.impl.presentation.BundleUi
import io.aequicor.heartbeat.feature.appupdate.impl.presentation.UpdateFailureUi
import io.aequicor.heartbeat.feature.appupdate.impl.presentation.UpdateStageUi
import io.aequicor.heartbeat.feature.appupdate.impl.resources.Res
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_available
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_back
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_beta
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_build
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_bundles
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_bundles_unknown
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_check
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_check_failed
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_checking
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_description
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_development
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_disabled
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_downloading
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_failure_corrupted
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_failure_install
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_failure_invalid
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_failure_manual
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_failure_network
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_failure_previous
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_failure_rate_limited
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_failure_space
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_failure_storage
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_idle
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_install
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_not_release
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_open_page
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_pending
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_platform_required
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_preparing
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_restarting
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_status
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_title
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_train
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_unsupported_platform
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_up_to_date
import io.aequicor.heartbeat.feature.appupdate.impl.resources.updates_version
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The version, the bundles and the update status inside the settings host or a standalone navigation entry. */
@Composable
internal fun AppUpdatesContent(
    state: AppUpdatesScreenState,
    onIntent: (AppUpdatesScreenIntent) -> Unit,
    onOpenPage: (String) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    HbColumn(
        modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).testTag("app-updates"),
        gap = HbTheme.spacing.none,
    ) {
        if (onBack != null) {
            HbPaneHeader(
                stringResource(Res.string.updates_title),
                navigation = { HbIconButton(HbIcons.ArrowLeft, stringResource(Res.string.updates_back), onBack) },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HbLazyColumn(
                Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth).fillMaxSize().testTag("updates-list"),
                gap = HbTheme.spacing.xl,
                contentPadding = PaddingValues(HbTheme.spacing.xl),
            ) {
                item(key = "description") {
                    HbText(
                        stringResource(Res.string.updates_description),
                        style = HbTheme.typography.caption,
                        color = HbTheme.colors.textSecondary,
                    )
                }
                item(key = "build") { BuildSection(state.build) }
                state.build?.takeIf { it.train != null }?.let { build ->
                    item(key = "bundles") { BundlesSection(build) }
                }
                item(key = "status") { StatusSection(state, onIntent, onOpenPage) }
            }
        }
    }
}

@Composable
private fun BuildSection(build: AppBuildUi?, modifier: Modifier = Modifier) {
    HbSettingsSection(stringResource(Res.string.updates_build), modifier) {
        HbSettingsRow(stringResource(Res.string.updates_version), Modifier.testTag("updates-version")) {
            HbText(
                build?.version ?: stringResource(Res.string.updates_development),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
            if (build?.isBeta == true) HbBadge(stringResource(Res.string.updates_beta))
        }
        if (build?.train != null) {
            HbDivider()
            HbSettingsRow(stringResource(Res.string.updates_train), Modifier.testTag("updates-train")) {
                HbText(build.train, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
            }
        }
        if (build?.pendingTrains?.isNotEmpty() == true) {
            HbDivider()
            HbSettingsRow(stringResource(Res.string.updates_pending), Modifier.testTag("updates-pending")) {
                HbText(
                    build.pendingTrains.joinToString(),
                    style = HbTheme.typography.caption,
                    color = HbTheme.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun BundlesSection(build: AppBuildUi, modifier: Modifier = Modifier) {
    HbSettingsSection(stringResource(Res.string.updates_bundles), modifier.testTag("updates-bundles")) {
        if (build.bundles.isEmpty()) {
            HbText(
                stringResource(Res.string.updates_bundles_unknown),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
        }
        build.bundles.forEachIndexed { index, bundle ->
            if (index > 0) HbDivider()
            HbSettingsRow(bundle.id, Modifier.testTag("updates-bundle-${bundle.id}")) {
                HbText(bundle.version, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
            }
        }
    }
}

@Composable
private fun StatusSection(
    state: AppUpdatesScreenState,
    onIntent: (AppUpdatesScreenIntent) -> Unit,
    onOpenPage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(stringResource(Res.string.updates_status), modifier) {
        HbText(
            statusText(state),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("updates-status"),
            style = HbTheme.typography.body,
        )
        if (state.stage == UpdateStageUi.Downloading && state.percent != null) {
            HbProgressBar(
                state.percent / PERCENT,
                Modifier.testTag("updates-progress"),
                stringResource(Res.string.updates_downloading, state.version.orEmpty()),
            )
        }
        state.failure?.let { failure ->
            HbBanner(stringResource(failure.message()), Modifier.testTag("updates-failure"))
        }
        HbButton(
            stringResource(Res.string.updates_check),
            { onIntent(AppUpdatesScreenIntent.Check) },
            Modifier.testTag("updates-check"),
            style = HbButtonStyle.Secondary,
            enabled = state.isCheckEnabled,
        )
        if (state.isInstallEnabled) {
            HbButton(
                stringResource(Res.string.updates_install),
                { onIntent(AppUpdatesScreenIntent.Install) },
                Modifier.testTag("updates-install"),
            )
        }
        val page = state.pageUrl
        if (state.stage == UpdateStageUi.ManualInstall && page != null) {
            HbButton(
                stringResource(Res.string.updates_open_page),
                { onOpenPage(page) },
                Modifier.testTag("updates-open-page"),
            )
        }
    }
}

@Composable
private fun statusText(state: AppUpdatesScreenState): String = when (state.stage) {
    UpdateStageUi.Available, UpdateStageUi.ManualInstall ->
        stringResource(Res.string.updates_available, state.version.orEmpty())

    UpdateStageUi.Downloading -> stringResource(Res.string.updates_downloading, state.version.orEmpty())

    UpdateStageUi.Preparing -> stringResource(Res.string.updates_preparing, state.version.orEmpty())

    UpdateStageUi.Restarting -> stringResource(Res.string.updates_restarting, state.version.orEmpty())

    UpdateStageUi.Idle -> stringResource(Res.string.updates_idle)

    UpdateStageUi.Disabled -> stringResource(Res.string.updates_disabled)

    UpdateStageUi.NotReleaseBuild -> stringResource(Res.string.updates_not_release)

    UpdateStageUi.UnsupportedPlatform -> stringResource(Res.string.updates_unsupported_platform)

    UpdateStageUi.Checking -> stringResource(Res.string.updates_checking)

    UpdateStageUi.UpToDate -> stringResource(Res.string.updates_up_to_date)

    UpdateStageUi.PlatformUpdateRequired -> stringResource(Res.string.updates_platform_required)

    UpdateStageUi.CheckFailed -> stringResource(Res.string.updates_check_failed)
}

private fun UpdateFailureUi.message(): StringResource = when (this) {
    UpdateFailureUi.Network -> Res.string.updates_failure_network
    UpdateFailureUi.RateLimited -> Res.string.updates_failure_rate_limited
    UpdateFailureUi.InvalidRelease -> Res.string.updates_failure_invalid
    UpdateFailureUi.Corrupted -> Res.string.updates_failure_corrupted
    UpdateFailureUi.NotEnoughSpace -> Res.string.updates_failure_space
    UpdateFailureUi.Storage -> Res.string.updates_failure_storage
    UpdateFailureUi.ManualInstallRequired -> Res.string.updates_failure_manual
    UpdateFailureUi.InstallFailed -> Res.string.updates_failure_install
    UpdateFailureUi.PreviousAttemptFailed -> Res.string.updates_failure_previous
}

private const val PERCENT = 100f

private val previewBuild = AppBuildUi(
    version = "1.4.0",
    train = "1.4.2",
    isBeta = false,
    bundles = listOf(BundleUi("foundation", "1.4.0"), BundleUi("studio", "1.4.2")),
)

@Preview
@Composable
private fun AppUpdatesLightPreview() {
    HbTheme(darkTheme = false) {
        AppUpdatesContent(AppUpdatesScreenState(build = previewBuild, stage = UpdateStageUi.UpToDate), {}, {}, null)
    }
}

@Preview
@Composable
private fun AppUpdatesDarkPreview() {
    HbTheme(darkTheme = true) {
        AppUpdatesContent(
            AppUpdatesScreenState(build = previewBuild, stage = UpdateStageUi.Available, version = "1.5.0"),
            {},
            {},
            null,
        )
    }
}
