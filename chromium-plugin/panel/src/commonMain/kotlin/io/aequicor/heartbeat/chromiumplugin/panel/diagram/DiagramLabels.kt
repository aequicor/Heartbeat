package io.aequicor.heartbeat.chromiumplugin.panel.diagram

import androidx.compose.runtime.Composable
import io.aequicor.heartbeat.chromiumplugin.panel.resources.Res
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_actual_size
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_busy
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_close
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_copy_failed
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_copy_source
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_failed
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_fit
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_insert_text
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_open_full_size
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_rendering
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_retry
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_send_text
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_show_diagram
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_show_source
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_source_copied
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_syntax_error
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_syntax_error_at_line
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_timeout
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_title
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_too_large
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_unavailable_action
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_zoom_in
import io.aequicor.heartbeat.chromiumplugin.panel.resources.diagram_zoom_out
import io.aequicor.heartbeat.ds.components.HbDiagramLabels
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun diagramLabels(): HbDiagramLabels = HbDiagramLabels(
    zoomIn = stringResource(Res.string.diagram_zoom_in),
    zoomOut = stringResource(Res.string.diagram_zoom_out),
    fit = stringResource(Res.string.diagram_fit),
    actualSize = stringResource(Res.string.diagram_actual_size),
    insertText = stringResource(Res.string.diagram_insert_text),
    sendText = stringResource(Res.string.diagram_send_text),
    unavailableAction = stringResource(Res.string.diagram_unavailable_action),
    rendering = stringResource(Res.string.diagram_rendering),
    diagram = stringResource(Res.string.diagram_title),
    openFullSize = stringResource(Res.string.diagram_open_full_size),
    close = stringResource(Res.string.diagram_close),
    syntaxErrorAtLine = stringResource(Res.string.diagram_syntax_error_at_line),
    syntaxError = stringResource(Res.string.diagram_syntax_error),
    tooLarge = stringResource(Res.string.diagram_too_large),
    timeout = stringResource(Res.string.diagram_timeout),
    busy = stringResource(Res.string.diagram_busy),
    failed = stringResource(Res.string.diagram_failed),
    retry = stringResource(Res.string.diagram_retry),
    showSource = stringResource(Res.string.diagram_show_source),
    showDiagram = stringResource(Res.string.diagram_show_diagram),
    copySource = stringResource(Res.string.diagram_copy_source),
    sourceCopied = stringResource(Res.string.diagram_source_copied),
    copyFailed = stringResource(Res.string.diagram_copy_failed),
)
