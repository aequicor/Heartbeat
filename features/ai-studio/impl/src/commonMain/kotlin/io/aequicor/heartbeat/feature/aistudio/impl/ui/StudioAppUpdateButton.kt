package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTooltip
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AppUpdateFailureUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AppUpdateStageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AppUpdateUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_available
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_cancel
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_confirm
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_confirm_message
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_confirm_title
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_downloading
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_downloading_status
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_failure_corrupted
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_failure_install
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_failure_invalid
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_failure_network
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_failure_previous
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_failure_rate_limited
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_failure_space
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_failure_storage
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_install
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_manual
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_manual_reason
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_percent
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_preparing
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_preparing_status
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_restarting
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_restarting_status
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_retry
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val log = Log.tag("StudioAppUpdate")

/**
 * The sidebar header's offer of a newer app version. While it can be acted on it is a button: install and restart
 * (after a confirmation while agents run, since quitting ends their turns), retry after a failure, or open the
 * release page when the app cannot replace itself. While the update runs it is a quiet status with its progress.
 * The version, the stage and the failure are its tooltip and its accessible state.
 */
@Composable
internal fun AppUpdateButton(
    update: AppUpdateUi,
    isAgentRunning: Boolean,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isConfirming by remember(update.stage) { mutableStateOf(false) }
    val links = LocalUriHandler.current
    val description = update.description()
    val onAction = when {
        update.stage == AppUpdateStageUi.ManualInstall -> { -> openReleasePage(links, update.pageUrl) }
        isAgentRunning -> { -> isConfirming = true }
        else -> onInstall
    }
    Box(modifier) {
        HbTooltip(description) {
            when (update.stage) {
                AppUpdateStageUi.Ready, AppUpdateStageUi.ManualInstall -> AppUpdateAction(update, description, onAction)

                AppUpdateStageUi.Downloading, AppUpdateStageUi.Preparing, AppUpdateStageUi.Restarting ->
                    AppUpdateStatus(update, description)
            }
        }
        if (isConfirming) {
            AppUpdateConfirmation(
                version = update.version,
                onDismiss = { isConfirming = false },
                onConfirm = {
                    isConfirming = false
                    onInstall()
                },
            )
        }
    }
}

/** Quitting for the update ends the turns of working agents: the user confirms it first. */
@Composable
private fun AppUpdateConfirmation(version: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    HbDialog(
        title = stringResource(Res.string.app_update_confirm_title, version),
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("app-update-confirm-dialog"),
        actions = {
            HbButton(
                text = stringResource(Res.string.app_update_cancel),
                onClick = onDismiss,
                style = HbButtonStyle.Ghost,
            )
            HbButton(
                text = stringResource(Res.string.app_update_confirm),
                onClick = onConfirm,
                modifier = Modifier.testTag("app-update-confirm"),
                style = HbButtonStyle.Danger,
            )
        },
    ) {
        HbText(stringResource(Res.string.app_update_confirm_message))
    }
}

/** Installs, retries or opens the release page; disabled only when a manual install has no page to open. */
@Composable
private fun AppUpdateAction(update: AppUpdateUi, description: String, onClick: () -> Unit) {
    HbButton(
        text = update.actionLabel(),
        onClick = onClick,
        modifier = Modifier.testTag("sidebar-app-update").semantics { stateDescription = description },
        style = if (update.isPlain) HbButtonStyle.Primary else HbButtonStyle.Secondary,
        enabled = update.stage == AppUpdateStageUi.Ready || update.pageUrl.isNotEmpty(),
        size = HbButtonSize.Small,
    )
}

/** The running update as quiet text; its progress is exposed to accessibility services. */
@Composable
private fun AppUpdateStatus(update: AppUpdateUi, description: String) {
    HbText(
        update.progressLabel(),
        Modifier.testTag("sidebar-app-update-status").semantics {
            stateDescription = description
            update.percent?.let { progressBarRangeInfo = ProgressBarRangeInfo(it / PERCENT, 0f..1f) }
        },
        style = HbTheme.typography.caption,
        color = HbTheme.colors.textSecondary,
        maxLines = 1,
    )
}

/** The release page opens in the system browser; the platform may refuse it, which is logged. */
private fun openReleasePage(links: UriHandler, url: String) {
    log.i { "Open app release page" }
    try {
        links.openUri(url)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "App release page could not be opened" }
    }
}

private val AppUpdateUi.isPlain: Boolean get() = stage == AppUpdateStageUi.Ready && failure == null

@Composable
private fun AppUpdateUi.actionLabel(): String = stringResource(
    when {
        stage == AppUpdateStageUi.ManualInstall -> Res.string.app_update_manual
        failure == null -> Res.string.app_update_install
        else -> Res.string.app_update_retry
    },
)

@Composable
private fun AppUpdateUi.progressLabel(): String = when (stage) {
    AppUpdateStageUi.Downloading -> percent?.let { stringResource(Res.string.app_update_percent, it) }
        ?: stringResource(Res.string.app_update_downloading)

    AppUpdateStageUi.Preparing -> stringResource(Res.string.app_update_preparing)

    AppUpdateStageUi.Ready, AppUpdateStageUi.ManualInstall, AppUpdateStageUi.Restarting ->
        stringResource(Res.string.app_update_restarting)
}

/** What the update is doing and why the last attempt ended. */
@Composable
private fun AppUpdateUi.description(): String {
    val stageText = when (stage) {
        AppUpdateStageUi.Ready -> stringResource(Res.string.app_update_available, version)
        AppUpdateStageUi.ManualInstall -> stringResource(Res.string.app_update_manual_reason, version)
        AppUpdateStageUi.Downloading -> stringResource(Res.string.app_update_downloading_status, version)
        AppUpdateStageUi.Preparing -> stringResource(Res.string.app_update_preparing_status, version)
        AppUpdateStageUi.Restarting -> stringResource(Res.string.app_update_restarting_status, version)
    }
    val failureText = failure?.let { stringResource(it.message()) }
    return listOfNotNull(stageText, failureText).joinToString("\n")
}

private fun AppUpdateFailureUi.message(): StringResource = when (this) {
    AppUpdateFailureUi.Network -> Res.string.app_update_failure_network
    AppUpdateFailureUi.RateLimited -> Res.string.app_update_failure_rate_limited
    AppUpdateFailureUi.InvalidRelease -> Res.string.app_update_failure_invalid
    AppUpdateFailureUi.Corrupted -> Res.string.app_update_failure_corrupted
    AppUpdateFailureUi.NotEnoughSpace -> Res.string.app_update_failure_space
    AppUpdateFailureUi.Storage -> Res.string.app_update_failure_storage
    AppUpdateFailureUi.InstallFailed -> Res.string.app_update_failure_install
    AppUpdateFailureUi.PreviousAttemptFailed -> Res.string.app_update_failure_previous
}

private const val PERCENT = 100f
