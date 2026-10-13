package io.aequicor.heartbeat.chromiumplugin.panel.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.chromiumplugin.panel.resources.Res
import io.aequicor.heartbeat.chromiumplugin.panel.resources.capture_attach
import io.aequicor.heartbeat.chromiumplugin.panel.resources.capture_capturing
import io.aequicor.heartbeat.chromiumplugin.panel.resources.capture_changed
import io.aequicor.heartbeat.chromiumplugin.panel.resources.capture_ready
import io.aequicor.heartbeat.chromiumplugin.panel.resources.capture_unreadable
import io.aequicor.heartbeat.chromiumplugin.panel.resources.capture_unsupported
import io.aequicor.heartbeat.chromiumplugin.panel.resources.dismiss
import io.aequicor.heartbeat.chromiumplugin.panel.resources.draft_recover
import io.aequicor.heartbeat.chromiumplugin.panel.resources.draft_recovery
import io.aequicor.heartbeat.chromiumplugin.panel.store.CaptureProblem
import io.aequicor.heartbeat.chromiumplugin.panel.store.StudioPanelIntent
import io.aequicor.heartbeat.chromiumplugin.panel.store.StudioPanelState
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import org.jetbrains.compose.resources.stringResource

/** Page content is retained when its destination changed; recovery never sends a previous prompt automatically. */
@Composable
internal fun StudioHostNotices(
    state: StudioPanelState,
    onIntent: (StudioPanelIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.none) {
        if (state.isCapturing) {
            HbBanner(stringResource(Res.string.capture_capturing), tone = HbTone.Neutral)
        }
        val problem = state.captureProblem
        if (state.capturedPage != null || problem != null) {
            val label = when (problem) {
                CaptureProblem.Unreadable -> Res.string.capture_unreadable
                CaptureProblem.Unsupported -> Res.string.capture_unsupported
                CaptureProblem.DestinationChanged -> Res.string.capture_changed
                null -> Res.string.capture_ready
            }
            StudioNotice(stringResource(label), tone = HbTone.Neutral) {
                if (state.capturedPage != null) {
                    HbButton(
                        stringResource(Res.string.capture_attach),
                        { onIntent(StudioPanelIntent.AttachCaptured) },
                        enabled = state.isInteractive,
                        size = HbButtonSize.Small,
                    )
                }
                HbButton(
                    stringResource(Res.string.dismiss),
                    { onIntent(StudioPanelIntent.DismissCapture) },
                    style = HbButtonStyle.Ghost,
                    size = HbButtonSize.Small,
                )
            }
        }
        if (state.recoveryDraft != null) {
            StudioNotice(
                stringResource(Res.string.draft_recovery),
                tone = HbTone.Warning,
            ) {
                HbButton(
                    stringResource(Res.string.draft_recover),
                    { onIntent(StudioPanelIntent.RecoverDraft) },
                    enabled = state.isInteractive,
                    style = HbButtonStyle.Secondary,
                    size = HbButtonSize.Small,
                )
                HbButton(
                    stringResource(Res.string.dismiss),
                    { onIntent(StudioPanelIntent.DismissRecovery) },
                    style = HbButtonStyle.Ghost,
                    size = HbButtonSize.Small,
                )
            }
        }
    }
}

/** Recovery actions stay below the message so long translations fit a narrow browser panel. */
@Composable
internal fun StudioNotice(
    message: String,
    modifier: Modifier = Modifier,
    tone: HbTone = HbTone.Neutral,
    actions: @Composable RowScope.() -> Unit = {},
) {
    HbColumn(modifier.fillMaxWidth().padding(HbTheme.spacing.s), gap = HbTheme.spacing.s) {
        HbBanner(message, tone = tone)
        HbRow(content = actions)
    }
}
