package io.aequicor.heartbeat.platform.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.arkivanov.decompose.ComponentContext
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDiagramLabels
import io.aequicor.heartbeat.ds.components.HbDiagramRenderer
import io.aequicor.heartbeat.ds.components.HbDiagramsProvider
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbWindowDragArea
import io.aequicor.heartbeat.ds.components.HbWindowStartupLoading
import io.aequicor.heartbeat.ds.layouts.HbBoxWithConstraints
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioRoute
import io.aequicor.heartbeat.feature.welcome.api.WelcomeRoute
import io.aequicor.heartbeat.platform.dibundle.HeartbeatGraph
import io.aequicor.heartbeat.platform.dibundle.root.HeartbeatRoot
import io.aequicor.heartbeat.platform.dibundle.root.RootStart
import io.aequicor.heartbeat.platform.root.RootContent
import io.aequicor.heartbeat.platform.shared.resources.Res
import io.aequicor.heartbeat.platform.shared.resources.computer_use_stop
import io.aequicor.heartbeat.platform.shared.resources.computer_use_stop_description
import io.aequicor.heartbeat.platform.shared.resources.computer_use_working
import io.aequicor.heartbeat.platform.shared.resources.diagram_actual_size
import io.aequicor.heartbeat.platform.shared.resources.diagram_busy
import io.aequicor.heartbeat.platform.shared.resources.diagram_close
import io.aequicor.heartbeat.platform.shared.resources.diagram_copy_failed
import io.aequicor.heartbeat.platform.shared.resources.diagram_copy_source
import io.aequicor.heartbeat.platform.shared.resources.diagram_failed
import io.aequicor.heartbeat.platform.shared.resources.diagram_fit
import io.aequicor.heartbeat.platform.shared.resources.diagram_insert_text
import io.aequicor.heartbeat.platform.shared.resources.diagram_open_full_size
import io.aequicor.heartbeat.platform.shared.resources.diagram_rendering
import io.aequicor.heartbeat.platform.shared.resources.diagram_retry
import io.aequicor.heartbeat.platform.shared.resources.diagram_send_text
import io.aequicor.heartbeat.platform.shared.resources.diagram_show_diagram
import io.aequicor.heartbeat.platform.shared.resources.diagram_show_source
import io.aequicor.heartbeat.platform.shared.resources.diagram_source_copied
import io.aequicor.heartbeat.platform.shared.resources.diagram_syntax_error
import io.aequicor.heartbeat.platform.shared.resources.diagram_syntax_error_at_line
import io.aequicor.heartbeat.platform.shared.resources.diagram_timeout
import io.aequicor.heartbeat.platform.shared.resources.diagram_title
import io.aequicor.heartbeat.platform.shared.resources.diagram_too_large
import io.aequicor.heartbeat.platform.shared.resources.diagram_unavailable_action
import io.aequicor.heartbeat.platform.shared.resources.diagram_zoom_in
import io.aequicor.heartbeat.platform.shared.resources.diagram_zoom_out
import org.jetbrains.compose.resources.stringResource

/**
 * Creates the root once outside composition, using the platform lifecycle. A profile always starts in the studio;
 * the engine runtime toggle only picks the studio backend.
 */
fun createAppRoot(context: ComponentContext, graph: HeartbeatGraph): HeartbeatRoot = HeartbeatRoot(
    context,
    graph,
    RootStart(guest = listOf(WelcomeRoute), profile = listOf(AiStudioRoute)),
    localProfile = ProfileId("local"),
)

/**
 * Shared rendering; while the profile is restored the window shows its plain backdrop. A host that draws diagram
 * fences of Markdown passes its [diagramRenderer] (desktop: PlantUML); without one they stay code.
 */
@Composable
fun App(
    root: HeartbeatRoot,
    modifier: Modifier = Modifier,
    diagramRenderer: HbDiagramRenderer? = null,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
) {
    HbTheme(darkTheme = darkTheme) {
        HbDiagramsProvider(diagramRenderer, diagramLabels()) {
            AppContent(root, modifier)
        }
    }
}

@Composable
private fun AppContent(root: HeartbeatRoot, modifier: Modifier = Modifier) {
    val computerUse by root.computerUse.collectAsState()
    HbColumn(modifier.fillMaxSize(), gap = HbTheme.spacing.none) {
        if (computerUse.isActive && HbTheme.dimensions.isDesktop) {
            // The fill sits on the drag area so it also covers the native caption controls' insets.
            HbWindowDragArea(
                Modifier.fillMaxWidth().height(
                    HbTheme.dimensions.headerHeight,
                ).background(HbTheme.surfaces.sidebar),
            ) {
                HbRow(
                    Modifier.fillMaxSize().padding(horizontal = HbTheme.spacing.m),
                    gap = HbTheme.spacing.m,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HbText(
                        stringResource(Res.string.computer_use_working),
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                        style = HbTheme.typography.caption,
                        maxLines = 1,
                    )
                    val stopDescription = stringResource(Res.string.computer_use_stop_description)
                    HbButton(
                        stringResource(Res.string.computer_use_stop),
                        root::stopComputerUse,
                        Modifier.testTag("computer-use-stop-agent")
                            .semantics { contentDescription = stopDescription },
                        style = HbButtonStyle.Danger,
                        size = HbButtonSize.Small,
                    )
                }
            }
        }
        RootContent(root, Modifier.weight(1f), loading = { RootLoading() })
    }
}

@Composable
private fun RootLoading() {
    HbWindowStartupLoading()
    HbBoxWithConstraints(Modifier.fillMaxSize().background(HbTheme.surfaces.backdrop)) {
        if (HbTheme.dimensions.isDesktop) {
            if (maxWidth >= HbTheme.dimensions.compactBreakpoint) {
                Box(
                    Modifier.fillMaxHeight().width(HbTheme.dimensions.sidebarWidth)
                        .background(HbTheme.surfaces.sidebar),
                )
            }
            HbWindowDragArea(Modifier.fillMaxWidth().height(HbTheme.dimensions.headerHeight)) { }
        }
    }
}

@Composable
private fun diagramLabels(): HbDiagramLabels = HbDiagramLabels(
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
