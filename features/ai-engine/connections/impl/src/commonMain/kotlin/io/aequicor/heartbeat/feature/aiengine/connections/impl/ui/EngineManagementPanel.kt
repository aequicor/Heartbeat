package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbProgressBar
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.CompatibilityUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineActionKindUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineActionUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineConnectionsScreenIntent
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EnginePanelUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.InstallSupportUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.JobPhaseUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.JobUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_cancel
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_check_updates
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_confirm
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_enabled
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_enabled_hint
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_install
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_installation
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_restart
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_revert
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_runtime
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_runtime_exited
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_runtime_stale
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_section
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_uninstall
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_unverified
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_update
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_update_available
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_dismiss
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_probe
import org.jetbrains.compose.resources.stringResource

/**
 * Management of the selected engine, between the engine list and its connections: the profile switch with the
 * reasons that keep the engine off, the installation with its update and removal actions, the running job and the
 * runtimes. Every action waits while another change or a job runs.
 */
@Composable
internal fun EngineManagementSection(
    panel: EnginePanelUi,
    isSaving: Boolean,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isIdle = !isSaving && panel.job?.isRunning != true
    HbSettingsSection(
        stringResource(Res.string.engine_section),
        modifier.testTag("engine-panel"),
        description = panel.title,
        trailingContent = {
            HbButton(
                stringResource(Res.string.settings_probe),
                { onIntent(EngineConnectionsScreenIntent.InspectEngine) },
                Modifier.testTag("engine-inspect"),
                HbButtonStyle.Ghost,
                enabled = isIdle && EngineActionKindUi.Inspect in panel.actions,
                size = HbButtonSize.Small,
            )
        },
    ) {
        EngineSwitchRow(panel, isIdle, onIntent)
        HbDivider()
        InstallationBlock(panel, isIdle, onIntent)
        panel.job?.let { job ->
            HbDivider()
            JobBlock(job, isSaving, onIntent)
        }
        HbDivider()
        RuntimeRow(panel, isIdle, onIntent)
    }
}

@Composable
private fun EngineSwitchRow(
    panel: EnginePanelUi,
    isIdle: Boolean,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(Res.string.engine_enabled)
    val reasons = panel.reasons.map { reasonText(it) }
    HbSettingsRow(
        title,
        modifier,
        description = reasons.joinToString(" ").ifEmpty { stringResource(Res.string.engine_enabled_hint) },
    ) {
        HbSwitch(
            panel.isUserEnabled,
            { onIntent(EngineConnectionsScreenIntent.SetEngineEnabled(it)) },
            title,
            Modifier.testTag("engine-enabled"),
            enabled = isIdle && panel.isSwitchable,
        )
    }
}

@Composable
private fun InstallationBlock(
    panel: EnginePanelUi,
    isIdle: Boolean,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val installation = panel.installation
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
        HbSettingsRow(stringResource(Res.string.engine_installation), description = installationSummary(installation)) {
            if (installation.compatibility == CompatibilityUi.Unverified) {
                HbBadge(stringResource(Res.string.engine_unverified), tone = HbTone.Warning)
            }
            val latest = installation.latest
            if (installation.isUpdateAvailable && latest != null) {
                HbBadge(stringResource(Res.string.engine_update_available, latest), tone = HbTone.Brand)
            }
        }
        installation.path?.let { path ->
            HbText(
                path,
                Modifier.padding(horizontal = HbTheme.spacing.m).testTag("engine-path"),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
        }
        installation.failure?.let { HbBanner(failureText(it), Modifier.testTag("engine-installation-failure")) }
        if (installation.support != InstallSupportUi.BuiltIn) {
            InstallationActions(panel, isIdle, onIntent)
        }
    }
}

@Composable
private fun InstallationActions(
    panel: EnginePanelUi,
    isIdle: Boolean,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = panel.actions
    val isBundled = panel.installation.support == InstallSupportUi.Bundled
    HbFlowRow(modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.m)) {
        if (EngineActionKindUi.Install in actions) {
            ActionButton(
                stringResource(Res.string.engine_install),
                "engine-install",
                isIdle,
                style = HbButtonStyle.Primary,
            ) {
                onIntent(EngineConnectionsScreenIntent.RequestEngineAction(EngineActionUi.Install))
            }
        }
        if (EngineActionKindUi.Update in actions) {
            ActionButton(
                stringResource(Res.string.engine_update),
                "engine-update",
                isIdle,
                style = HbButtonStyle.Primary,
            ) {
                onIntent(EngineConnectionsScreenIntent.RequestEngineAction(EngineActionUi.Update))
            }
        }
        ActionButton(
            stringResource(Res.string.engine_check_updates),
            "engine-check-updates",
            isIdle && EngineActionKindUi.CheckForUpdates in actions,
        ) { onIntent(EngineConnectionsScreenIntent.CheckUpdates) }
        if (EngineActionKindUi.Uninstall in actions) {
            ActionButton(
                stringResource(if (isBundled) Res.string.engine_revert else Res.string.engine_uninstall),
                "engine-uninstall",
                isIdle,
            ) { onIntent(EngineConnectionsScreenIntent.RequestEngineAction(EngineActionUi.Uninstall)) }
        }
    }
}

@Composable
private fun JobBlock(
    job: JobUi,
    isSaving: Boolean,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier.fillMaxWidth().padding(HbTheme.spacing.m).testTag("engine-job"), gap = HbTheme.spacing.s) {
        HbText(
            "${jobTitle(job.action)} · ${phaseText(job.phase)}",
            style = HbTheme.typography.label,
            color = if (job.phase is JobPhaseUi.Failed) HbTheme.colors.error else HbTheme.colors.textPrimary,
        )
        val phase = job.phase
        if (phase is JobPhaseUi.Downloading) {
            phase.progress?.let { HbProgressBar(it, Modifier.testTag("engine-job-progress")) }
        }
        if (phase is JobPhaseUi.Failed) HbBanner(failureText(phase.failure))
        HbFlowRow {
            if (job.isRunning) {
                ActionButton(stringResource(Res.string.conn_cancel), "engine-job-cancel", !isSaving) {
                    onIntent(EngineConnectionsScreenIntent.CancelEngineJob)
                }
            } else {
                ActionButton(stringResource(Res.string.settings_dismiss), "engine-job-dismiss", !isSaving) {
                    onIntent(EngineConnectionsScreenIntent.DismissEngineJob)
                }
            }
        }
    }
}

@Composable
private fun RuntimeRow(
    panel: EnginePanelUi,
    isIdle: Boolean,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val runtime = panel.runtime
    HbSettingsRow(stringResource(Res.string.engine_runtime), modifier, description = runtimeSummary(runtime)) {
        if (runtime.isStale) HbBadge(stringResource(Res.string.engine_runtime_stale), tone = HbTone.Warning)
        if (runtime.hasExited) HbBadge(stringResource(Res.string.engine_runtime_exited), tone = HbTone.Danger)
        HbButton(
            stringResource(Res.string.engine_restart),
            { onIntent(EngineConnectionsScreenIntent.RestartEngine) },
            Modifier.testTag("engine-restart"),
            HbButtonStyle.Ghost,
            enabled = isIdle && EngineActionKindUi.Restart in panel.actions,
            size = HbButtonSize.Small,
        )
    }
}

/** Confirms removals, an unverified update and sign-out before they start. */
@Composable
internal fun EngineActionDialog(
    action: EngineActionUi,
    panel: EnginePanelUi,
    isEnabled: Boolean,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
) {
    val (title, body) = confirmationTexts(action, panel)
    HbDialog(
        title,
        onDismissRequest = { onIntent(EngineConnectionsScreenIntent.DismissEngineAction) },
        actions = {
            HbButton(
                stringResource(Res.string.conn_cancel),
                { onIntent(EngineConnectionsScreenIntent.DismissEngineAction) },
                style = HbButtonStyle.Ghost,
            )
            HbButton(
                stringResource(Res.string.engine_confirm),
                { onIntent(EngineConnectionsScreenIntent.ConfirmEngineAction) },
                Modifier.testTag("engine-action-confirm"),
                style = if (action == EngineActionUi.Update) HbButtonStyle.Primary else HbButtonStyle.Danger,
                enabled = isEnabled,
            )
        },
    ) {
        HbText(body, color = HbTheme.colors.textSecondary)
    }
}

/** A small panel action identified by [tag] in UI tests. */
@Composable
internal fun ActionButton(
    label: String,
    tag: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    style: HbButtonStyle = HbButtonStyle.Secondary,
    onClick: () -> Unit,
) {
    HbButton(label, onClick, modifier.testTag(tag), style, enabled = enabled, size = HbButtonSize.Small)
}
