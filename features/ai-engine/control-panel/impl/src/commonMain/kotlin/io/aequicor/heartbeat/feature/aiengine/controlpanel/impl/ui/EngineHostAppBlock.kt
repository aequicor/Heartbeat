package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineActionKindUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineManagementIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EnginePanelUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.HostAppUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_host_app_config
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_host_app_in_use
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_host_app_installed
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_host_app_login_hint
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_host_app_missing
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_host_app_no_runtime
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_host_app_open
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_host_app_runtime
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_host_app_runtime_pending
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_host_app_runtimes
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_host_app_title
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_host_app_unchecked
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_host_app_version
import org.jetbrains.compose.resources.stringResource

/**
 * The engine's host application (the Claude app for Claude Code): whether it is installed, its version and path,
 * the runtimes it keeps with the one in use, the configuration directory it shares with the engine, and a button
 * that opens it. Which runtime the engine runs is chosen in the launch settings.
 */
@Composable
internal fun HostAppBlock(
    panel: EnginePanelUi,
    hostApp: HostAppUi,
    isIdle: Boolean,
    onIntent: (EngineManagementIntent) -> Unit,
    modifier: Modifier = Modifier,
    isVisuallyIdle: Boolean = isIdle,
) {
    val isOpenable = EngineActionKindUi.OpenHostApp in panel.actions
    HbColumn(modifier.fillMaxWidth().testTag("engine-host-app"), gap = HbTheme.spacing.s) {
        HbSettingsRow(
            stringResource(Res.string.engine_host_app_title, hostApp.title),
            description = hostAppSummary(hostApp),
        ) {
            if (isOpenable) {
                ActionButton(
                    stringResource(Res.string.engine_host_app_open),
                    "engine-host-app-open",
                    isIdle,
                    visualEnabled = isVisuallyIdle,
                ) { onIntent(EngineManagementIntent.OpenHostApp) }
            }
        }
        if (hostApp.isInUse) {
            HbFlowRow(Modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.m)) {
                HbBadge(stringResource(Res.string.engine_host_app_in_use), tone = HbTone.Brand)
            }
        }
        hostApp.path?.let { InstallationPath(it) }
        hostApp.failure?.let {
            HbBanner(failureText(it), Modifier.padding(horizontal = HbTheme.spacing.m))
        }
        if (hostApp.isChecked) HostAppDetails(hostApp, Modifier.padding(horizontal = HbTheme.spacing.m))
    }
}

@Composable
private fun HostAppDetails(hostApp: HostAppUi, modifier: Modifier = Modifier) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
        if (hostApp.runtimes.isNotEmpty()) {
            HbText(stringResource(Res.string.engine_host_app_runtimes), style = HbTheme.typography.label)
            hostApp.runtimes.forEach { runtime ->
                val line = stringResource(Res.string.engine_host_app_runtime, runtime.version, runtime.build)
                val pending = stringResource(Res.string.engine_host_app_runtime_pending)
                HbText(
                    if (runtime.isVerified) line else "$line — $pending",
                    Modifier.testTag("engine-host-runtime:${runtime.version}:${runtime.build}"),
                    style = HbTheme.typography.caption,
                    color = HbTheme.colors.textSecondary,
                )
            }
        }
        if (hostApp.isInstalled && !hostApp.hasRuntime) {
            HbBanner(stringResource(Res.string.engine_host_app_no_runtime), tone = HbTone.Warning)
        }
        hostApp.configDirectory?.let {
            HbText(
                stringResource(Res.string.engine_host_app_config, it),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
        }
        HbText(
            stringResource(Res.string.engine_host_app_login_hint),
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun hostAppSummary(hostApp: HostAppUi): String = when {
    !hostApp.isChecked -> stringResource(Res.string.engine_host_app_unchecked)

    !hostApp.isInstalled -> stringResource(Res.string.engine_host_app_missing)

    else -> hostApp.version?.let { stringResource(Res.string.engine_host_app_version, it) }
        ?: stringResource(Res.string.engine_host_app_installed)
}
