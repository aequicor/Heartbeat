package io.aequicor.heartbeat.ds.catalog

import androidx.compose.runtime.Composable
import io.aequicor.heartbeat.ds.components.HbToolLabels
import io.aequicor.heartbeat.ds.resources.HbString
import io.aequicor.heartbeat.ds.resources.hbString

@Composable
internal fun catalogToolLabels(): HbToolLabels = HbToolLabels(
    expand = hbString(HbString.ExpandTool),
    collapse = hbString(HbString.CollapseTool),
    running = hbString(HbString.Working),
    complete = hbString(HbString.Ready),
    error = hbString(HbString.Failed),
    details = hbString(HbString.ToolDetails),
    console = hbString(HbString.ToolConsole),
    diff = hbString(HbString.ToolDiff),
    copyFilePath = hbString(HbString.CopyFilePath),
    filePathCopied = hbString(HbString.FilePathCopied),
    unknownFile = hbString(HbString.UnknownFile),
)
