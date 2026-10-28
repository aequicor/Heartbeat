package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.components.HbActivityIndicator
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbChip
import io.aequicor.heartbeat.ds.components.HbComposerAction
import io.aequicor.heartbeat.ds.components.HbComposerMenuButton
import io.aequicor.heartbeat.ds.components.HbComposerMenuStyle
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.components.HbTooltip
import io.aequicor.heartbeat.ds.components.hbSurface
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_chip_autopilot
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_chip_off
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_chip_suggest
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_menu
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_mode_autopilot
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_mode_autopilot_hint
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_mode_off
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_mode_off_hint
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_mode_suggest
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_mode_suggest_hint
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_notice_delivery
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_notice_done
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_notice_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_notice_limit
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_notice_looping
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_notice_needs_user
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_notice_reason
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_notice_restarted
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_notice_took_over
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_notice_turn_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_notice_unavailable
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_notice_user_stopped
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_paused
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_refresh
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_running
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_sending
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_stop
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_suggest_title
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_thinking
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueModeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueNoticeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueUi
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

/** Icon of an enabled mode; disabled chats enable auto-continue through the composer's add menu. */
@Composable
internal fun AutoContinueModeMenu(content: PaneContent, onIntent: (StudioIntent) -> Unit) {
    val chat = content.pane.sessionId ?: return
    val ui = content.autoContinue ?: return
    if (ui.mode == AutoContinueModeUi.Off) return
    var isExpanded by remember { mutableStateOf(false) }
    val label = when (ui.mode) {
        AutoContinueModeUi.Off -> stringResource(Res.string.auto_continue_chip_off)

        AutoContinueModeUi.Suggest -> stringResource(Res.string.auto_continue_chip_suggest)

        AutoContinueModeUi.Autopilot ->
            stringResource(Res.string.auto_continue_chip_autopilot, ui.autoTurns, content.autoContinueLimit)
    }
    HbComposerMenuButton(
        label = label,
        actions = autoContinueModeActions(ui.mode),
        isExpanded = isExpanded,
        onExpandedChange = { isExpanded = it },
        onAction = { id ->
            isExpanded = false
            AutoContinueModeUi.entries.firstOrNull { it.name == id }?.let {
                onIntent(StudioIntent.SetAutoContinue(chat, it))
            }
        },
        modifier = Modifier.testTag("auto-continue-mode-${content.pane.id}"),
        headerLabel = stringResource(Res.string.auto_continue_menu),
        icon = if (ui.mode == AutoContinueModeUi.Autopilot) HbIcons.Play else HbIcons.Sparkles,
        style = HbComposerMenuStyle.AccentCircle,
    )
}

/** Shared choices for the active-mode icon and the add menu. */
@Composable
internal fun autoContinueModeActions(current: AutoContinueModeUi) = persistentListOf(
    modeAction(
        AutoContinueModeUi.Off,
        current,
        Res.string.auto_continue_mode_off,
        Res.string.auto_continue_mode_off_hint,
    ),
    modeAction(
        AutoContinueModeUi.Suggest,
        current,
        Res.string.auto_continue_mode_suggest,
        Res.string.auto_continue_mode_suggest_hint,
    ),
    modeAction(
        AutoContinueModeUi.Autopilot,
        current,
        Res.string.auto_continue_mode_autopilot,
        Res.string.auto_continue_mode_autopilot_hint,
    ),
)

@Composable
private fun modeAction(
    mode: AutoContinueModeUi,
    current: AutoContinueModeUi,
    label: StringResource,
    hint: StringResource,
): HbComposerAction = HbComposerAction(
    id = mode.name,
    label = stringResource(label),
    supportingText = stringResource(hint),
    isSelected = mode == current,
    leadingIcon = when (mode) {
        AutoContinueModeUi.Off -> HbIcons.Pause
        AutoContinueModeUi.Suggest -> HbIcons.Sparkles
        AutoContinueModeUi.Autopilot -> HbIcons.Play
    },
)

/**
 * What auto-continue shows above the composer: the continuer at work, the compact autopilot status and stop action, a
 * pause or a notice, and the offered replies. A click inserts a reply into the composer without sending it.
 */
@Composable
internal fun AutoContinueStrip(content: PaneContent, onIntent: (StudioIntent) -> Unit, modifier: Modifier = Modifier) {
    val chat = content.pane.sessionId ?: return
    val ui = content.autoContinue?.takeIf { it.mode != AutoContinueModeUi.Off } ?: return
    if (content.session?.isRunning == true && ui.mode != AutoContinueModeUi.Autopilot) return
    HbColumn(modifier.testTag("auto-continue-${content.pane.id}"), gap = HbTheme.spacing.s) {
        if (ui.mode == AutoContinueModeUi.Autopilot) {
            AutoContinueRunning(chat, ui.isPaused, onIntent)
        } else {
            AutoContinueStatus(ui)
        }
        ui.notice?.let { notice ->
            val message = stringResource(notice.message())
            val reason = ui.reason.take(MAX_SHOWN_CHARS)
            val text = if (reason.isBlank()) {
                message
            } else {
                stringResource(Res.string.auto_continue_notice_reason, message, reason)
            }
            HbBanner(text, tone = notice.tone())
        }
        if (ui.isPaused && ui.mode != AutoContinueModeUi.Autopilot) {
            HbBanner(stringResource(Res.string.auto_continue_paused), tone = HbTone.Neutral) {
                HbButton(
                    stringResource(Res.string.auto_continue_stop),
                    { onIntent(StudioIntent.StopAutoContinue(chat)) },
                    style = HbButtonStyle.Ghost,
                    size = HbButtonSize.Small,
                )
            }
        }
        if (ui.mode == AutoContinueModeUi.Suggest) AutoContinueOptions(content.pane.id, chat, ui, onIntent)
    }
}

@Composable
private fun AutoContinueRunning(chat: String, isPaused: Boolean, onIntent: (StudioIntent) -> Unit) {
    HbRow(
        Modifier.fillMaxWidth()
            .hbSurface(HbTheme.surfaces.composer, HbTheme.shapes.medium)
            .padding(horizontal = HbTheme.spacing.l, vertical = HbTheme.spacing.s),
        gap = HbTheme.spacing.m,
    ) {
        if (isPaused) {
            HbIcon(HbIcons.Pause, null, Modifier.size(HbTheme.dimensions.iconSmallSize), tint = HbTheme.colors.brand)
        } else {
            HbActivityIndicator()
        }
        HbText(
            stringResource(if (isPaused) Res.string.auto_continue_paused else Res.string.auto_continue_running),
            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            style = HbTheme.typography.label,
            maxLines = 1,
        )
        HbButton(
            stringResource(Res.string.auto_continue_stop),
            { onIntent(StudioIntent.StopAutoContinue(chat)) },
            style = HbButtonStyle.Accent,
            size = HbButtonSize.Small,
        )
    }
}

@Composable
private fun AutoContinueStatus(ui: AutoContinueUi) {
    val status = when {
        ui.isThinking -> Res.string.auto_continue_thinking
        ui.isSending -> Res.string.auto_continue_sending
        else -> null
    } ?: return
    HbText(
        stringResource(status),
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = HbTheme.typography.caption,
        color = HbTheme.colors.textSecondary,
    )
}

@Composable
private fun AutoContinueOptions(paneId: Int, chat: String, ui: AutoContinueUi, onIntent: (StudioIntent) -> Unit) {
    if (ui.countdown != null || ui.isThinking || ui.isSending) return
    if (ui.options.isEmpty() && ui.notice == null) return
    HbColumn(
        Modifier.fillMaxWidth()
            .hbSurface(HbTheme.surfaces.composer, HbTheme.shapes.medium)
            .padding(HbTheme.spacing.l),
        gap = HbTheme.spacing.l,
    ) {
        if (ui.options.isNotEmpty()) {
            HbText(stringResource(Res.string.auto_continue_suggest_title), style = HbTheme.typography.body)
        }
        AutoContinueChoices(paneId, chat, ui, onIntent)
    }
}

@Composable
private fun AutoContinueChoices(paneId: Int, chat: String, ui: AutoContinueUi, onIntent: (StudioIntent) -> Unit) {
    HbFlowRow(gap = HbTheme.spacing.s) {
        ui.options.forEachIndexed { index, option ->
            HbTooltip(option.text) {
                HbChip(
                    label = option.label,
                    modifier = Modifier.background(HbTheme.surfaces.composerPill, HbTheme.shapes.small)
                        .testTag("auto-continue-option-$paneId-$index"),
                    onClick = { onIntent(StudioIntent.PickAutoContinue(chat, paneId, index, false, option.text)) },
                    trailingIcon = null,
                    accessibleLabel = option.text,
                )
            }
        }
        HbIconButton(
            HbIcons.Refresh,
            stringResource(Res.string.auto_continue_refresh),
            { onIntent(StudioIntent.RefreshAutoContinue(chat)) },
            Modifier.testTag("auto-continue-refresh-$paneId"),
        )
    }
}

/** Localized explanations for terminal and interrupted continuation states. */
private fun AutoContinueNoticeUi.message(): StringResource = when (this) {
    AutoContinueNoticeUi.Done -> Res.string.auto_continue_notice_done
    AutoContinueNoticeUi.NeedsUser -> Res.string.auto_continue_notice_needs_user
    AutoContinueNoticeUi.LimitReached -> Res.string.auto_continue_notice_limit
    AutoContinueNoticeUi.Looping -> Res.string.auto_continue_notice_looping
    AutoContinueNoticeUi.ContinuerFailed -> Res.string.auto_continue_notice_failed
    AutoContinueNoticeUi.ContinuerUnavailable -> Res.string.auto_continue_notice_unavailable
    AutoContinueNoticeUi.DeliveryFailed -> Res.string.auto_continue_notice_delivery
    AutoContinueNoticeUi.TurnFailed -> Res.string.auto_continue_notice_turn_failed
    AutoContinueNoticeUi.UserStopped -> Res.string.auto_continue_notice_user_stopped
    AutoContinueNoticeUi.HumanTookOver -> Res.string.auto_continue_notice_took_over
    AutoContinueNoticeUi.Restarted -> Res.string.auto_continue_notice_restarted
}

private fun AutoContinueNoticeUi.tone(): HbTone = when (this) {
    AutoContinueNoticeUi.Done -> HbTone.Success

    AutoContinueNoticeUi.NeedsUser -> HbTone.Brand

    AutoContinueNoticeUi.ContinuerFailed,
    AutoContinueNoticeUi.ContinuerUnavailable,
    AutoContinueNoticeUi.DeliveryFailed,
    AutoContinueNoticeUi.TurnFailed,
    -> HbTone.Warning

    AutoContinueNoticeUi.LimitReached,
    AutoContinueNoticeUi.Looping,
    AutoContinueNoticeUi.UserStopped,
    AutoContinueNoticeUi.HumanTookOver,
    AutoContinueNoticeUi.Restarted,
    -> HbTone.Neutral
}

/** Model text shown in a banner at most; the full reply is still sent and can be edited. */
private const val MAX_SHOWN_CHARS = 300
