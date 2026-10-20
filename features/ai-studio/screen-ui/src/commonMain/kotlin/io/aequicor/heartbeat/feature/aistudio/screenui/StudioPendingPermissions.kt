package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbPanel
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState

/** Background organisms can await consent without a chat record or an open pane. */
@Composable
internal fun StudioPendingPermissions(
    state: AiStudioScreenState,
    visibleChats: Set<String>,
    onIntent: (AiStudioScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pending = state.organisms.filter { (id, organism) -> id !in visibleChats && organism.permissions.isNotEmpty() }
    if (pending.isEmpty()) return
    HbPanel(modifier.testTag("studio-pending-permissions")) {
        HbColumn(Modifier.hbVerticalScroll(rememberScrollState()), gap = HbTheme.spacing.none) {
            pending.forEach { (organismId, organism) ->
                HbText(state.sessions.firstOrNull { it.id == organismId }?.title ?: organismId)
                organism.permissions.forEach { request ->
                    key(organismId, request.cell, request.requestId) {
                        OrganismPermissionCard(organismId, request, HbTheme.dimensions.toolPayloadMaxHeight, onIntent)
                    }
                }
            }
        }
    }
}

internal fun AiStudioScreenState.visiblePermissionChats(
    isSplitAllowed: Boolean,
    isCompact: Boolean,
    isEmbedded: Boolean,
): Set<String> {
    if (isEmbedded) return emptySet()
    val displayed = if (isSplitAllowed && !isCompact) panes else panes.filter { it.id == focusedPaneId }
    return displayed.mapNotNull { pane ->
        val content = paneContent(pane)
        pane.sessionId.takeIf { content.session?.isOrganism == true && !content.isNativeChild }
    }.toSet()
}
