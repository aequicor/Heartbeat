package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTooltip
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
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_downloading_percent
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
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_preparing
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_restarting
import io.aequicor.heartbeat.feature.aistudio.impl.resources.app_update_retry
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The sidebar header's offer of a newer app version: installs it and restarts (after a confirmation while agents
 * run, since quitting ends their turns), shows the download and preparation, retries after a failure, or opens the
 * release page when the app cannot replace itself.
 */
@Composable
internal fun AppUpdateButton(
    update: AppUpdateUi,
    isAgentRunning: Boolean,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isConfirming by remember { mutableStateOf(false) }
    val links = LocalUriHandler.current
    val failure = update.failure?.let { stringResource(it.message()) }
    val tooltip = listOfNotNull(stringResource(Res.string.app_update_available, update.version), failure)
        .joinToString("\n")
    HbTooltip(tooltip, modifier) {
        HbButton(
            text = update.label(),
            onClick = {
                when (update.stage) {
                    AppUpdateStageUi.Ready -> if (isAgentRunning) isConfirming = true else onInstall()
                    AppUpdateStageUi.ManualInstall -> links.openUri(update.pageUrl)
                    AppUpdateStageUi.Downloading, AppUpdateStageUi.Preparing, AppUpdateStageUi.Restarting -> Unit
                }
            },
            modifier = Modifier.testTag("sidebar-app-update"),
            style = if (update.failure == null) HbButtonStyle.Primary else HbButtonStyle.Secondary,
            enabled = update.stage == AppUpdateStageUi.Ready ||
                (update.stage == AppUpdateStageUi.ManualInstall && update.pageUrl.isNotEmpty()),
            size = HbButtonSize.Small,
        )
    }
    if (isConfirming) {
        val dismiss = { isConfirming = false }
        HbDialog(
            stringResource(Res.string.app_update_confirm_title, update.version),
            dismiss,
            Modifier.testTag("app-update-confirm-dialog"),
            actions = {
                HbButton(stringResource(Res.string.app_update_cancel), dismiss, style = HbButtonStyle.Ghost)
                HbButton(
                    stringResource(Res.string.app_update_confirm),
                    {
                        isConfirming = false
                        onInstall()
                    },
                    Modifier.testTag("app-update-confirm"),
                )
            },
        ) {
            HbText(stringResource(Res.string.app_update_confirm_message))
        }
    }
}

@Composable
private fun AppUpdateUi.label(): String = when (stage) {
    AppUpdateStageUi.Ready ->
        stringResource(if (failure == null) Res.string.app_update_install else Res.string.app_update_retry)

    AppUpdateStageUi.ManualInstall -> stringResource(Res.string.app_update_manual)

    AppUpdateStageUi.Downloading -> percent?.let { stringResource(Res.string.app_update_downloading_percent, it) }
        ?: stringResource(Res.string.app_update_downloading)

    AppUpdateStageUi.Preparing -> stringResource(Res.string.app_update_preparing)

    AppUpdateStageUi.Restarting -> stringResource(Res.string.app_update_restarting)
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
