package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbCopyButton
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
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.CompatibilityUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineActionKindUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineActionUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineConnectionsScreenIntent
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EnginePanelUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.InstallSupportUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.JobPhaseUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.JobUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ManagementFailureUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_cancel
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_check_updates
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_copy_failed
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_enabled
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_enabled_hint
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_install
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_installation
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_job_status
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_not_runnable
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_path_copied
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_path_copy
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
 * reasons that keep the engine off, the installation with its update and removal actions, the CLI sign-in, the
 * running job, the runtimes and the launch settings. Every action waits while another change or a job runs.
 */
@Composable
internal fun EngineManagementSection(
    panel: EnginePanelUi,
    loginCode: String,
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
        panel.login?.let { login ->
            HbDivider()
            EngineLoginBlock(panel, login, loginCode, isIdle, onIntent)
        }
        panel.job?.let { job ->
            HbDivider()
            JobBlock(job, isSaving, onIntent)
        }
        HbDivider()
        RuntimeRow(panel, isIdle, onIntent)
        panel.launch?.let { launch ->
            HbDivider()
            EngineLaunchForm(launch, isIdle, onIntent)
        }
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
        HbSettingsRow(stringResource(Res.string.engine_installation), description = installationSummary(installation))
        // Badges get their own wrapping row so the row's text keeps its width on a phone.
        val latest = installation.latest?.takeIf { installation.isUpdateAvailable }
        val isUnverified = installation.compatibility == CompatibilityUi.Unverified
        if (isUnverified || latest != null || !installation.isRunnable) {
            HbFlowRow(Modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.m)) {
                if (!installation.isRunnable) {
                    HbBadge(stringResource(Res.string.engine_not_runnable), tone = HbTone.Warning)
                }
                if (isUnverified) HbBadge(stringResource(Res.string.engine_unverified), tone = HbTone.Warning)
                latest?.let { HbBadge(stringResource(Res.string.engine_update_available, it), tone = HbTone.Brand) }
            }
        }
        installation.path?.let { InstallationPath(it) }
        installation.failure?.let {
            HbBanner(
                failureText(it),
                Modifier.padding(horizontal = HbTheme.spacing.m).testTag("engine-installation-failure"),
            )
        }
        if (installation.support != InstallSupportUi.BuiltIn) {
            InstallationActions(panel, isIdle, onIntent)
        }
    }
}

@Composable
private fun InstallationPath(path: String, modifier: Modifier = Modifier) {
    HbRow(modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.m), gap = HbTheme.spacing.s) {
        HbText(
            path,
            Modifier.weight(1f).testTag("engine-path"),
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
        HbCopyButton(
            path,
            stringResource(Res.string.engine_path_copy),
            stringResource(Res.string.engine_path_copied),
            stringResource(Res.string.engine_copy_failed),
        )
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
    val phase = job.phase
    HbColumn(modifier.fillMaxWidth().padding(HbTheme.spacing.m).testTag("engine-job"), gap = HbTheme.spacing.s) {
        JobTitle(job)
        if (phase is JobPhaseUi.Downloading) {
            phase.progress?.let { HbProgressBar(it, Modifier.testTag("engine-job-progress")) }
        }
        if (phase is JobPhaseUi.Failed) JobFailure(phase.failure)
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

/** The action and its phase; a failure is told once, by [JobFailure], and byte counts are not announced. */
@Composable
private fun JobTitle(job: JobUi, modifier: Modifier = Modifier) {
    val phase = job.phase
    val title = if (phase is JobPhaseUi.Failed) {
        jobTitle(job.action)
    } else {
        stringResource(Res.string.engine_job_status, jobTitle(job.action), phaseText(phase))
    }
    val isAnnounced = phase !is JobPhaseUi.Downloading
    HbText(
        title,
        modifier.semantics { if (isAnnounced) liveRegion = LiveRegionMode.Polite },
        style = HbTheme.typography.label,
    )
}

/** A sign-in that needs a terminal is an instruction, not an error. */
@Composable
private fun JobFailure(failure: ManagementFailureUi, modifier: Modifier = Modifier) {
    val isInstruction = (failure as? ManagementFailureUi.Login)?.terminalCommand != null
    HbBanner(failureText(failure), modifier, tone = if (isInstruction) HbTone.Warning else HbTone.Danger)
}

@Composable
private fun RuntimeRow(
    panel: EnginePanelUi,
    isIdle: Boolean,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val runtime = panel.runtime
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
        HbSettingsRow(stringResource(Res.string.engine_runtime), description = runtimeSummary(runtime)) {
            HbButton(
                stringResource(Res.string.engine_restart),
                { onIntent(EngineConnectionsScreenIntent.RestartEngine) },
                Modifier.testTag("engine-restart"),
                HbButtonStyle.Ghost,
                enabled = isIdle && EngineActionKindUi.Restart in panel.actions,
                size = HbButtonSize.Small,
            )
        }
        if (runtime.isStale || runtime.hasExited) {
            HbFlowRow(Modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.m)) {
                if (runtime.isStale) HbBadge(stringResource(Res.string.engine_runtime_stale), tone = HbTone.Warning)
                if (runtime.hasExited) HbBadge(stringResource(Res.string.engine_runtime_exited), tone = HbTone.Danger)
            }
        }
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
    val (title, body, confirm) = confirmationTexts(action, panel) ?: return
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
                confirm,
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
