package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val log = Log.tag("DS/Diff")
private enum class PathCopyStatus { Idle, Copying, Copied, Failed }

@Composable
internal fun HbDiffHeader(filePath: String?, labels: HbToolLabels, modifier: Modifier = Modifier) {
    val square = CornerSize(HbTheme.elevation.none)
    val shape = HbTheme.shapes.small.copy(bottomStart = square, bottomEnd = square)
    HbRow(
        modifier = modifier.fillMaxWidth().background(HbTheme.colors.surfaceElevated, shape)
            .heightIn(min = HbTheme.dimensions.touchTarget).padding(start = HbTheme.spacing.m),
        gap = HbTheme.spacing.xs,
    ) {
        HbText(
            text = filePath ?: labels.unknownFile,
            modifier = Modifier.weight(1f).semantics { heading() },
            style = HbTheme.typography.code,
            maxLines = 1,
        )
        if (filePath != null) {
            DisableSelection { CopyPathButton(filePath, labels) }
        }
    }
}

@Composable
private fun CopyPathButton(filePath: String, labels: HbToolLabels, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var status by remember(filePath) { mutableStateOf(PathCopyStatus.Idle) }
    val feedbackMillis = HbTheme.motion.copyFeedbackMillis
    LaunchedEffect(filePath, status) {
        if (status == PathCopyStatus.Copied || status == PathCopyStatus.Failed) {
            delay(feedbackMillis.toLong())
            log.d { "copy feedback cleared" }
            status = PathCopyStatus.Idle
        }
    }
    val description = when (status) {
        PathCopyStatus.Copied -> labels.filePathCopied
        PathCopyStatus.Failed -> labels.error
        PathCopyStatus.Idle, PathCopyStatus.Copying -> labels.copyFilePath
    }
    ComposerIconButton(
        icon = when (status) {
            PathCopyStatus.Copied -> HbIcons.Check
            PathCopyStatus.Failed -> HbIcons.Alert
            PathCopyStatus.Copying -> HbIcons.More
            PathCopyStatus.Idle -> HbIcons.Copy
        },
        label = "${labels.copyFilePath}: $filePath",
        modifier = modifier.semantics {
            stateDescription = description
            liveRegion = LiveRegionMode.Polite
        },
        enabled = status != PathCopyStatus.Copying,
        onClick = {
            log.i { "copy file path requested length=${filePath.length}" }
            status = PathCopyStatus.Copying
            scope.launch {
                try {
                    clipboard.setClipEntry(hbPlainTextClipEntry(filePath))
                    log.d { "file path copied" }
                    status = PathCopyStatus.Copied
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.e(error) { "file path copy failed" }
                    status = PathCopyStatus.Failed
                }
            }
        },
    )
}
