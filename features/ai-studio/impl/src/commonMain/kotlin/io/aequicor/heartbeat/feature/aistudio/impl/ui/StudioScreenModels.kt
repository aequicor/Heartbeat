package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.RenameUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SettingsUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SidebarUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlin.time.Duration

/** Navigation leaving the studio, provided by its component. */
@Immutable
internal class StudioExits(val onBack: () -> Unit, val onOpenToggles: () -> Unit)

/** What a pane may offer in the current window layout. */
@Immutable
internal data class PaneLayout(val canSplit: Boolean, val canClose: Boolean, val isCompact: Boolean)

/**
 * Everything one pane renders. Equal values skip the pane, so a streamed chunk or the elapsed-time tick of one
 * session recomposes only the pane showing it. [elapsed] is set only while the session runs.
 */
@Immutable
internal data class PaneContent(
    val pane: PaneUi,
    val session: SessionUi?,
    val project: ProjectUi?,
    val projects: ImmutableList<ProjectUi>,
    val transcript: ImmutableList<MessageUi>?,
    val isFocused: Boolean,
    val isStopping: Boolean,
    val elapsed: Duration?,
    val draft: String,
    val isSubmitFailed: Boolean,
    val renaming: RenameUi?,
    val settings: SettingsUi,
)

/** Sidebar data only: transcripts and drafts do not recompose the session lists. */
@Immutable
internal data class SidebarInput(
    val projects: ImmutableList<ProjectUi>,
    val sessions: ImmutableList<SessionUi>,
    val running: ImmutableSet<String>,
    val sidebar: SidebarUi,
    val selectedId: String?,
    val newSessionProjectId: String?,
)

/** Rename origin of the header of [paneId]. */
internal fun paneOrigin(paneId: Int): String = "pane:$paneId"

internal fun AiStudioScreenState.paneContent(pane: PaneUi): PaneContent {
    val session = session(pane.sessionId)
    val startedAt = pane.sessionId?.let { id -> transcripts[id]?.lastOrNull { it is MessageUi.Prompt }?.createdAt }
    return PaneContent(
        pane = pane,
        session = session,
        project = project(pane.projectId ?: session?.projectId),
        projects = projects,
        transcript = pane.sessionId?.let { transcripts[it] },
        isFocused = pane.id == focusedPaneId,
        isStopping = session != null && session.id in stopping,
        elapsed = if (session?.isRunning == true) startedAt?.let { (now - it).coerceAtLeast(Duration.ZERO) } else null,
        draft = draft(pane.id),
        isSubmitFailed = pane.id in failedPanes,
        renaming = sidebar.renaming?.takeIf { it.origin == paneOrigin(pane.id) },
        settings = settings,
    )
}

internal fun AiStudioScreenState.sidebarInput(): SidebarInput {
    val focused = panes.firstOrNull { it.id == focusedPaneId }
    return SidebarInput(
        projects = projects,
        sessions = sessions,
        running = running,
        sidebar = sidebar,
        selectedId = focused?.sessionId,
        newSessionProjectId = focused?.projectId
            ?: session(focused?.sessionId)?.projectId
            ?: projects.firstOrNull()?.id,
    )
}
