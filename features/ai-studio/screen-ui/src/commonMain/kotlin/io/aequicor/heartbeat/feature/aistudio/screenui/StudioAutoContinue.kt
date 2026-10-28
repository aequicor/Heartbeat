package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbChip
import io.aequicor.heartbeat.ds.components.HbComposerAction
import io.aequicor.heartbeat.ds.components.HbComposerMenuButton
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_chip_autopilot
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_chip_off
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_chip_suggest
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_countdown
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_countdown_text
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_edit
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_insert
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
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_send_now
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_sending
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_stop
import io.aequicor.heartbeat.feature.aistudio.impl.resources.auto_continue_thinking
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueCountdownUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueModeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueNoticeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AutoContinueUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Instant
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

/** Composer menu of the chat's auto-continue mode; the label names the current mode and the autopilot's progress. */
@Composable
internal fun AutoContinueModeMenu(content: PaneContent, onIntent: (StudioIntent) -> Unit) {
    val chat = content.pane.sessionId ?: return
    val ui = content.autoContinue ?: return
    var isExpanded by remember { mutableStateOf(false) }
    val label = when (ui.mode) {
        AutoContinueModeUi.Off -> stringResource(Res.string.auto_continue_chip_off)

        AutoContinueModeUi.Suggest -> stringResource(Res.string.auto_continue_chip_suggest)

        AutoContinueModeUi.Autopilot ->
            stringResource(Res.string.auto_continue_chip_autopilot, ui.autoTurns, content.autoContinueLimit)
    }
    val actions = persistentListOf(
        modeAction(
            AutoContinueModeUi.Off,
            ui.mode,
            Res.string.auto_continue_mode_off,
            Res.string.auto_continue_mode_off_hint,
        ),
        modeAction(
            AutoContinueModeUi.Suggest,
            ui.mode,
            Res.string.auto_continue_mode_suggest,
            Res.string.auto_continue_mode_suggest_hint,
        ),
        modeAction(
            AutoContinueModeUi.Autopilot,
            ui.mode,
            Res.string.auto_continue_mode_autopilot,
            Res.string.auto_continue_mode_autopilot_hint,
        ),
    )
    HbComposerMenuButton(
        label = label,
        actions = actions,
        isExpanded = isExpanded,
        onExpandedChange = { isExpanded = it },
        onAction = { id ->
            isExpanded = false
            AutoContinueModeUi.entries.firstOrNull { it.name == id }?.let {
                onIntent(
                    StudioIntent.SetAutoContinue(chat, it),
                )
            }
        },
        modifier = Modifier.testTag("auto-continue-mode-${content.pane.id}"),
        headerLabel = stringResource(Res.string.auto_continue_menu),
        icon = HbIcons.Sparkles,
    )
}

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
)

/**
 * What auto-continue shows above the composer: the continuer at work, the autopilot's countdown with its controls, a
 * pause or a notice, and the offered replies. A click sends a reply as the human's message; the pencil next to it puts
 * the reply into the composer for editing.
 */
@Composable
internal fun AutoContinueStrip(content: PaneContent, onIntent: (StudioIntent) -> Unit, modifier: Modifier = Modifier) {
    val chat = content.pane.sessionId ?: return
    val ui = content.autoContinue?.takeIf { it.mode != AutoContinueModeUi.Off } ?: return
    if (content.session?.isRunning == true) return
    HbColumn(modifier.testTag("auto-continue-${content.pane.id}"), gap = HbTheme.spacing.s) {
        ui.countdown?.let { AutoContinueCountdown(chat, it, onIntent) }
        AutoContinueStatus(ui)
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
        if (ui.isPaused) {
            HbBanner(stringResource(Res.string.auto_continue_paused), tone = HbTone.Neutral) {
                HbButton(
                    stringResource(Res.string.auto_continue_stop),
                    { onIntent(StudioIntent.StopAutoContinue(chat)) },
                    style = HbButtonStyle.Ghost,
                    size = HbButtonSize.Small,
                )
            }
        }
        AutoContinueOptions(content.pane.id, chat, ui, onIntent)
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
    HbFlowRow(gap = HbTheme.spacing.s) {
        ui.options.forEachIndexed { index, option ->
            HbRow(gap = HbTheme.spacing.xs, verticalAlignment = Alignment.CenterVertically) {
                HbChip(
                    label = option.label,
                    modifier = Modifier.testTag("auto-continue-option-$paneId-$index"),
                    icon = HbIcons.Send,
                    onClick = { onIntent(StudioIntent.PickAutoContinue(chat, paneId, index, true, option.text)) },
                    trailingIcon = null,
                    accessibleLabel = option.text,
                )
                HbIconButton(
                    HbIcons.Edit,
                    stringResource(Res.string.auto_continue_insert, option.label),
                    { onIntent(StudioIntent.PickAutoContinue(chat, paneId, index, false, option.text)) },
                    Modifier.testTag("auto-continue-insert-$paneId-$index"),
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

/**
 * The reply the autopilot is about to send with its controls. The text is announced once; the seconds left come from
 * the countdown's deadline, so a redrawn view stays in step, and are not announced every second. The controls wrap
 * under the text on a narrow pane.
 */
@Composable
private fun AutoContinueCountdown(chat: String, countdown: AutoContinueCountdownUi, onIntent: (StudioIntent) -> Unit) {
    var left by remember(chat, countdown.seq) { mutableIntStateOf(secondsLeft(countdown)) }
    LaunchedEffect(chat, countdown.seq) {
        while (left > 0) {
            delay(SECOND_MILLIS)
            left = secondsLeft(countdown)
        }
    }
    HbColumn(Modifier.testTag("auto-continue-countdown"), gap = HbTheme.spacing.xs) {
        HbBanner(
            stringResource(Res.string.auto_continue_countdown_text, countdown.text.take(MAX_SHOWN_CHARS)),
            tone = HbTone.Brand,
        )
        HbFlowRow(gap = HbTheme.spacing.s) {
            HbText(
                stringResource(Res.string.auto_continue_countdown, left),
                Modifier.clearAndSetSemantics {},
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
            HbButton(
                stringResource(Res.string.auto_continue_send_now),
                { onIntent(StudioIntent.SendAutoContinueNow(chat)) },
                size = HbButtonSize.Small,
            )
            HbButton(
                stringResource(Res.string.auto_continue_edit),
                { onIntent(StudioIntent.EditAutoContinue(chat)) },
                style = HbButtonStyle.Secondary,
                size = HbButtonSize.Small,
            )
            HbButton(
                stringResource(Res.string.auto_continue_stop),
                { onIntent(StudioIntent.StopAutoContinue(chat)) },
                style = HbButtonStyle.Ghost,
                size = HbButtonSize.Small,
            )
        }
    }
}

/** Whole seconds left until [countdown] sends; its full length when the deadline is unknown. */
private fun secondsLeft(countdown: AutoContinueCountdownUi): Int {
    if (countdown.deadline == Instant.DISTANT_PAST) return countdown.seconds
    val left = (countdown.deadline - Clock.System.now()).inWholeMilliseconds
    return ((left + SECOND_MILLIS - 1) / SECOND_MILLIS).toInt().coerceIn(0, countdown.seconds)
}

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

private const val SECOND_MILLIS = 1_000L

/** Model text shown in a banner at most; the full reply is still sent and can be edited. */
private const val MAX_SHOWN_CHARS = 300
