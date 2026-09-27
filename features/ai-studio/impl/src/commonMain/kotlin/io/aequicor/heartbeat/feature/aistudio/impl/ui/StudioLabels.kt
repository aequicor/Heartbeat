package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Composable
import io.aequicor.heartbeat.ds.components.HbToolLabels
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.author_agent
import io.aequicor.heartbeat.feature.aistudio.impl.resources.author_studio
import io.aequicor.heartbeat.feature.aistudio.impl.resources.author_you
import io.aequicor.heartbeat.feature.aistudio.impl.resources.duration_minutes
import io.aequicor.heartbeat.feature.aistudio.impl.resources.duration_seconds
import io.aequicor.heartbeat.feature.aistudio.impl.resources.run_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.stopped_after
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_collapse
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_complete
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_console
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_copy_path
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_details
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_diff
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_error
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_expand
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_path_copied
import io.aequicor.heartbeat.feature.aistudio.impl.resources.tool_running
import org.jetbrains.compose.resources.stringResource

/** Transcript copy for [section], resolved in the current language. */
@Composable
internal fun timelineLabels(section: String): TimelineLabels = TimelineLabels(
    section = section,
    you = stringResource(Res.string.author_you),
    agent = stringResource(Res.string.author_agent),
    studio = stringResource(Res.string.author_studio),
    stoppedTemplate = stringResource(Res.string.stopped_after),
    failed = stringResource(Res.string.run_failed),
    durations = durationLabels(),
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
)
