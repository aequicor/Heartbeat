package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Composable
import io.aequicor.heartbeat.ds.components.HbMessageAlignment
import io.aequicor.heartbeat.ds.components.HbMessageAppearance
import io.aequicor.heartbeat.ds.components.HbToolLabels
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.author_agent
import io.aequicor.heartbeat.feature.aistudio.impl.resources.author_studio
import io.aequicor.heartbeat.feature.aistudio.impl.resources.author_you
import io.aequicor.heartbeat.feature.aistudio.impl.resources.date_today
import io.aequicor.heartbeat.feature.aistudio.impl.resources.date_yesterday
import io.aequicor.heartbeat.feature.aistudio.impl.resources.duration_minutes
import io.aequicor.heartbeat.feature.aistudio.impl.resources.duration_seconds
import io.aequicor.heartbeat.feature.aistudio.impl.resources.message_copied
import io.aequicor.heartbeat.feature.aistudio.impl.resources.message_copy
import io.aequicor.heartbeat.feature.aistudio.impl.resources.reasoning_title
import io.aequicor.heartbeat.feature.aistudio.impl.resources.run_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.stopped_after
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_cancelled
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_collapse
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_complete
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_console
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_copy_path
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_details
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_diff
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_error
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_expand
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_path_copied
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_pending
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_running
import org.jetbrains.compose.resources.stringResource

/** Transcript copy for [section], resolved in the current language. */
@Composable
internal fun timelineLabels(section: String, calendar: StudioCalendar = StudioCalendar()): TimelineLabels =
    TimelineLabels(
        section = section,
        you = stringResource(Res.string.author_you),
        agent = stringResource(Res.string.author_agent),
        studio = stringResource(Res.string.author_studio),
        stoppedTemplate = stringResource(Res.string.stopped_after),
        failed = stringResource(Res.string.run_failed),
        durations = durationLabels(),
        promptAppearance = HbMessageAppearance(
            background = HbTheme.surfaces.outgoing,
            foreground = HbTheme.colors.textPrimary,
            isContentWidth = true,
            isAuthorVisible = false,
        ),
        replyAppearance = HbMessageAppearance(
            alignment = HbMessageAlignment.Center,
            background = HbTheme.surfaces.assistant,
            foreground = HbTheme.colors.textPrimary,
            widthFraction = 1f,
            isUnified = true,
        ),
        reasoning = stringResource(Res.string.reasoning_title),
        today = stringResource(Res.string.date_today),
        yesterday = stringResource(Res.string.date_yesterday),
        calendar = calendar,
        isGroupedByDate = true,
    )

/** Elapsed-time templates in the current language. */
@Composable
internal fun durationLabels(): DurationLabels = DurationLabels(
    secondsTemplate = stringResource(Res.string.duration_seconds),
    minutesTemplate = stringResource(Res.string.duration_minutes),
)

/** Tool call disclosure copy in the current language. */
@Composable
internal fun studioToolLabels(): HbToolLabels = HbToolLabels(
    expand = stringResource(Res.string.tool_expand),
    collapse = stringResource(Res.string.tool_collapse),
    running = stringResource(Res.string.tool_running),
    complete = stringResource(Res.string.tool_complete),
    error = stringResource(Res.string.tool_error),
    details = stringResource(Res.string.tool_details),
    console = stringResource(Res.string.tool_console),
    diff = stringResource(Res.string.tool_diff),
    copyFilePath = stringResource(Res.string.tool_copy_path),
    filePathCopied = stringResource(Res.string.tool_path_copied),
    unknownFile = stringResource(Res.string.tool_diff),
    pending = stringResource(Res.string.tool_pending),
    cancelled = stringResource(Res.string.tool_cancelled),
    copyMessage = stringResource(Res.string.message_copy),
    messageCopied = stringResource(Res.string.message_copied),
)
