package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.CompatibilityUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineActionKindUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineActionUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineManagementIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EnginePanelUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.InstallSupportUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.JobPhaseUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.JobUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.LaunchOptionUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ManagementFailureUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_cancel
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_dismiss
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_probe
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_status_off
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_status_on
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_check_updates
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_copy_failed
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_disabled_profile
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_enable
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_install
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_installation
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_job_status
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_mcp_claude_scope
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_mcp_description
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_mcp_pi_requirement
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_mcp_title
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_not_runnable
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_packages
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_packages_description
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_packages_sync
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_packages_update
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_path_copied
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_path_copy
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_restart
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_revert
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_runtime
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_runtime_exited
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_runtime_stale
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_section
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_switch_label
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_uninstall
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_uninstall_blocked
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_unverified
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_update
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_update_available
import org.jetbrains.compose.resources.stringResource

/** The profile switch stays in the panel header in both enabled and disabled states. */
@Composable
internal fun EngineSwitchHeader(
    panel: EnginePanelUi,
    isIdle: Boolean,
    onIntent: (EngineManagementIntent) -> Unit,
    modifier: Modifier = Modifier,
    isVisuallyIdle: Boolean = isIdle,
) {
    HbRow(modifier, gap = HbTheme.spacing.s) {
        HbText(
            stringResource(if (panel.isEnabled) Res.string.control_status_on else Res.string.control_status_off),
            style = HbTheme.typography.label,
        )
        HbSwitch(
            panel.isUserEnabled,
            { onIntent(EngineManagementIntent.SetEngineEnabled(it)) },
            stringResource(Res.string.engine_switch_label, panel.title),
            Modifier.testTag("engine-enabled"),
            enabled = isIdle && panel.isSwitchable,
            visualEnabled = isVisuallyIdle && panel.isSwitchable,
        )
    }
}

/**
 * Enabled engines show all applicable management blocks. A disabled overview keeps only its reason, enabling and a
 * safe managed-copy removal; an already running job remains visible and cancellable. Every action waits for other work.
 */
@Composable
internal fun EngineManagementSection(
    panel: EnginePanelUi,
    loginCode: String,
    isSaving: Boolean,
    onIntent: (EngineManagementIntent) -> Unit,
    modifier: Modifier = Modifier,
    isSavingVisible: Boolean = isSaving,
) {
    val isIdle = !isSaving && panel.job?.isRunning != true
    val isVisuallyIdle = !isSavingVisible && panel.job?.isRunning != true
    if (!panel.isEnabled) {
        DisabledEngineContent(panel, isIdle, isSaving, onIntent, modifier, isVisuallyIdle)
        return
    }
    // The inspection result is kept in memory only: refresh an unchecked installation once its panel is shown, so a
    // restarted app does not keep «Не проверен» until the user presses the button. The check is local and bounded.
    InspectUncheckedInstallation(panel, isIdle, onIntent)
    HbSettingsSection(
        stringResource(Res.string.engine_section),
        modifier.testTag("engine-panel"),
        trailingContent = {
            HbButton(
                stringResource(Res.string.control_probe),
                { onIntent(EngineManagementIntent.InspectEngine) },
                Modifier.testTag("engine-inspect"),
                HbButtonStyle.Ghost,
                enabled = isIdle && EngineActionKindUi.Inspect in panel.actions,
                visualEnabled = isVisuallyIdle && EngineActionKindUi.Inspect in panel.actions,
                size = HbButtonSize.Small,
            )
        },
    ) {
        InstallationBlock(panel, isIdle, onIntent, isVisuallyIdle = isVisuallyIdle)
        panel.hostApp?.let { hostApp ->
            HbDivider()
            HostAppBlock(panel, hostApp, isIdle, onIntent, isVisuallyIdle = isVisuallyIdle)
        }
        panel.login?.let { login ->
            HbDivider()
            EngineLoginBlock(
                panel,
                login,
                loginCode,
                isIdle,
                onIntent,
                isVisuallyIdle = isVisuallyIdle,
                isSaving = isSaving,
                isSavingVisible = isSavingVisible,
            )
        }
        panel.job?.let { job ->
            HbDivider()
            JobBlock(job, isSaving, onIntent, isSavingVisible = isSavingVisible)
        }
        if (EngineActionKindUi.Packages in panel.actions) {
            HbDivider()
            PackagesRow(isIdle, onIntent, isVisuallyIdle = isVisuallyIdle)
        }
        HbDivider()
        RuntimeRow(panel, isIdle, onIntent, isVisuallyIdle = isVisuallyIdle)
        panel.launch?.let { launch ->
            EngineMcpRow(panel, isIdle, onIntent)
            HbDivider()
            EngineLaunchForm(launch, isIdle, onIntent, isVisuallyIdle = isVisuallyIdle)
        }
    }
}

/** Inspects an installation whose previous result is unknown, once per shown panel and only while it is idle. */
@Composable
private fun InspectUncheckedInstallation(
    panel: EnginePanelUi,
    isIdle: Boolean,
    onIntent: (EngineManagementIntent) -> Unit,
) {
    val isUnchecked = !panel.installation.isChecked && panel.installation.failure == null
    val canInspect = isIdle && EngineActionKindUi.Inspect in panel.actions
    val currentIntent by rememberUpdatedState(onIntent)
    LaunchedEffect(isUnchecked, canInspect) {
        if (isUnchecked && canInspect) currentIntent(EngineManagementIntent.InspectEngine)
    }
}

@Composable
private fun DisabledEngineContent(
    panel: EnginePanelUi,
    isIdle: Boolean,
    isSaving: Boolean,
    onIntent: (EngineManagementIntent) -> Unit,
    modifier: Modifier = Modifier,
    isVisuallyIdle: Boolean = isIdle,
) {
    HbColumn(modifier.fillMaxWidth().testTag("engine-panel"), gap = HbTheme.spacing.m) {
        val reason = if (!panel.isUserEnabled) {
            stringResource(Res.string.engine_disabled_profile, panel.title)
        } else {
            panel.reasons.map { reasonText(it) }.joinToString(" ")
        }
        HbBanner(reason, tone = HbTone.Neutral)
        HbFlowRow(Modifier.fillMaxWidth()) {
            if (!panel.isUserEnabled) {
                ActionButton(
                    stringResource(Res.string.engine_enable),
                    "engine-enable",
                    isIdle && panel.isSwitchable,
                    visualEnabled = isVisuallyIdle && panel.isSwitchable,
                ) {
                    onIntent(EngineManagementIntent.SetEngineEnabled(true))
                }
            }
            if (panel.isUninstallAllowed) {
                ActionButton(
                    stringResource(Res.string.engine_uninstall),
                    "engine-uninstall",
                    isIdle,
                    visualEnabled = isVisuallyIdle,
                    style = HbButtonStyle.Danger,
                ) {
                    onIntent(EngineManagementIntent.RequestEngineAction(EngineActionUi.Uninstall))
                }
            }
        }
        panel.job?.let { JobBlock(it, isSaving, onIntent, isSavingVisible = isSaving) }
    }
}

/** Saved native MCP permission, separate from any launch draft currently being edited. */
@Composable
private fun EngineMcpRow(panel: EnginePanelUi, isIdle: Boolean, onIntent: (EngineManagementIntent) -> Unit) {
    val launch = panel.launch ?: return
    if (LaunchOptionUi.Mcp !in launch.options) return
    val hint = when (panel.id) {
        "pi" -> stringResource(Res.string.engine_mcp_pi_requirement)
        "claude" -> stringResource(Res.string.engine_mcp_claude_scope)
        else -> null
    }
    val description = listOfNotNull(stringResource(Res.string.engine_mcp_description), hint).joinToString(" ")
    HbDivider()
    HbSettingsRow(stringResource(Res.string.engine_mcp_title), description = description) {
        HbSwitch(
            launch.isMcpEnabled,
            { onIntent(EngineManagementIntent.SetMcpEnabled(it)) },
            stringResource(Res.string.engine_mcp_title),
            Modifier.testTag("engine-mcp-enabled"),
            enabled = isIdle && !launch.isEditing && panel.runtime.activeTurns == 0,
        )
    }
}

@Composable
private fun InstallationBlock(
    panel: EnginePanelUi,
    isIdle: Boolean,
    onIntent: (EngineManagementIntent) -> Unit,
    modifier: Modifier = Modifier,
    isVisuallyIdle: Boolean = isIdle,
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
        if (installation.support != InstallSupportUi.BuiltIn && installation.support != InstallSupportUi.External) {
            InstallationActions(panel, isIdle, onIntent, isVisuallyIdle = isVisuallyIdle)
        }
    }
}

@Composable
internal fun InstallationPath(
    path: String,
    modifier: Modifier = Modifier,
    tag: String = "engine-path",
    copyLabel: String = stringResource(Res.string.engine_path_copy),
) {
    HbRow(modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.m), gap = HbTheme.spacing.s) {
        HbText(
            path,
            Modifier.weight(1f).testTag(tag),
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
        HbCopyButton(
            path,
            copyLabel,
            stringResource(Res.string.engine_path_copied),
            stringResource(Res.string.engine_copy_failed),
        )
    }
}

@Composable
private fun InstallationActions(
    panel: EnginePanelUi,
    isIdle: Boolean,
    onIntent: (EngineManagementIntent) -> Unit,
    modifier: Modifier = Modifier,
    isVisuallyIdle: Boolean = isIdle,
) {
    val actions = panel.actions
    val isBundled = panel.installation.support == InstallSupportUi.Bundled
    HbFlowRow(modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.m)) {
        if (EngineActionKindUi.Install in actions) {
            ActionButton(
                stringResource(Res.string.engine_install),
                "engine-install",
                isIdle,
                visualEnabled = isVisuallyIdle,
                style = HbButtonStyle.Primary,
            ) {
                onIntent(EngineManagementIntent.RequestEngineAction(EngineActionUi.Install))
            }
        }
        if (EngineActionKindUi.Update in actions) {
            ActionButton(
                stringResource(Res.string.engine_update),
                "engine-update",
                isIdle,
                visualEnabled = isVisuallyIdle,
                style = HbButtonStyle.Primary,
            ) {
                onIntent(EngineManagementIntent.RequestEngineAction(EngineActionUi.Update))
            }
        }
        ActionButton(
            stringResource(Res.string.engine_check_updates),
            "engine-check-updates",
            isIdle && EngineActionKindUi.CheckForUpdates in actions,
            visualEnabled = isVisuallyIdle && EngineActionKindUi.CheckForUpdates in actions,
        ) { onIntent(EngineManagementIntent.CheckUpdates) }
        if (panel.isUninstallAllowed) {
            ActionButton(
                stringResource(if (isBundled) Res.string.engine_revert else Res.string.engine_uninstall),
                "engine-uninstall",
                isIdle,
                visualEnabled = isVisuallyIdle,
                style = HbButtonStyle.Danger,
            ) { onIntent(EngineManagementIntent.RequestEngineAction(EngineActionUi.Uninstall)) }
        }
    }
}

@Composable
private fun JobBlock(
    job: JobUi,
    isSaving: Boolean,
    onIntent: (EngineManagementIntent) -> Unit,
    modifier: Modifier = Modifier,
    isSavingVisible: Boolean = isSaving,
) {
    val phase = job.phase
    HbColumn(modifier.fillMaxWidth().padding(HbTheme.spacing.m).testTag("engine-job"), gap = HbTheme.spacing.s) {
        JobTitle(job)
        if (phase is JobPhaseUi.Downloading) {
            phase.progress?.let { HbProgressBar(it, Modifier.testTag("engine-job-progress")) }
        }
        if (phase is JobPhaseUi.Failed) JobFailure(phase.failure)
        if (job.isRunning) {
            ActionButton(
                stringResource(Res.string.control_cancel),
                "engine-job-cancel",
                !isSaving,
                visualEnabled = !isSavingVisible,
            ) {
                onIntent(EngineManagementIntent.CancelEngineJob)
            }
        } else {
            ActionButton(
                stringResource(Res.string.control_dismiss),
                "engine-job-dismiss",
                !isSaving,
                visualEnabled = !isSavingVisible,
            ) {
                onIntent(EngineManagementIntent.DismissEngineJob)
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

/** Installs the packages the configuration lists, or updates the installed ones, as a job of the engine. */
@Composable
private fun PackagesRow(
    isIdle: Boolean,
    onIntent: (EngineManagementIntent) -> Unit,
    modifier: Modifier = Modifier,
    isVisuallyIdle: Boolean = isIdle,
) {
    HbSettingsRow(
        stringResource(Res.string.engine_packages),
        modifier.testTag("engine-packages"),
        description = stringResource(Res.string.engine_packages_description),
    ) {
        ActionButton(
            stringResource(Res.string.engine_packages_update),
            "engine-packages-update",
            isIdle,
            visualEnabled = isVisuallyIdle,
        ) {
            onIntent(EngineManagementIntent.RequestEngineAction(EngineActionUi.UpdatePackages))
        }
        ActionButton(
            stringResource(Res.string.engine_packages_sync),
            "engine-packages-sync",
            isIdle,
            visualEnabled = isVisuallyIdle,
        ) {
            onIntent(EngineManagementIntent.RequestEngineAction(EngineActionUi.SyncPackages))
        }
    }
}

@Composable
private fun RuntimeRow(
    panel: EnginePanelUi,
    isIdle: Boolean,
    onIntent: (EngineManagementIntent) -> Unit,
    modifier: Modifier = Modifier,
    isVisuallyIdle: Boolean = isIdle,
) {
    val runtime = panel.runtime
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
        HbSettingsRow(stringResource(Res.string.engine_runtime), description = runtimeSummary(runtime)) {
            HbButton(
                stringResource(Res.string.engine_restart),
                { onIntent(EngineManagementIntent.RestartEngine) },
                Modifier.testTag("engine-restart"),
                HbButtonStyle.Ghost,
                enabled = isIdle && EngineActionKindUi.Restart in panel.actions,
                visualEnabled = isVisuallyIdle && EngineActionKindUi.Restart in panel.actions,
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
    onIntent: (EngineManagementIntent) -> Unit,
) {
    val (title, body, confirm) = confirmationTexts(action, panel) ?: return
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(action) {
        withFrameNanos { }
        withFrameNanos { }
        cancelFocus.requestFocus()
    }
    HbDialog(
        title,
        onDismissRequest = { onIntent(EngineManagementIntent.DismissEngineAction) },
        actions = {
            HbButton(
                stringResource(Res.string.control_cancel),
                { onIntent(EngineManagementIntent.DismissEngineAction) },
                Modifier.testTag("engine-action-cancel").focusRequester(cancelFocus),
                style = HbButtonStyle.Ghost,
            )
            HbButton(
                confirm,
                { onIntent(EngineManagementIntent.ConfirmEngineAction) },
                Modifier.testTag("engine-action-confirm"),
                style = if (action == EngineActionUi.Update) HbButtonStyle.Primary else HbButtonStyle.Danger,
                enabled = isEnabled && (action != EngineActionUi.Uninstall || panel.isUninstallAllowed),
            )
        },
    ) {
        HbText(body, color = HbTheme.colors.textSecondary)
        if (action == EngineActionUi.Uninstall && !panel.isUninstallAllowed) {
            HbBanner(stringResource(Res.string.engine_uninstall_blocked), tone = HbTone.Warning)
        }
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
    visualEnabled: Boolean = enabled,
    onClick: () -> Unit,
) {
    HbButton(
        label,
        onClick,
        modifier.testTag(tag),
        style,
        enabled = enabled,
        size = HbButtonSize.Small,
        visualEnabled = visualEnabled,
    )
}
