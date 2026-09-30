package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ContextUsageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ModelUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PermissionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ProviderUsageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.RenameUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionConfigurationUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SettingsUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SidebarUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.time.Duration

/**
 * Navigation leaving the studio, provided by its component. With [onOpenSettings] the sidebar shows one
 * "Settings" action; otherwise (unified settings off) it keeps the separate toggles, profile and connection actions.
 */
@Immutable
internal data class StudioExits(
    val onBack: () -> Unit,
    val onOpenToggles: () -> Unit,
    val onOpenProfileSettings: (() -> Unit)? = null,
    val onOpenConnections: (() -> Unit)? = null,
    val onOpenResearch: ((String) -> Unit)? = null,
    val questions: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
    val onOpenSettings: (() -> Unit)? = null,
)

/** What a pane may offer in the current window layout. */
@Immutable
internal data class PaneLayout(val isSplitAllowed: Boolean, val isCloseAllowed: Boolean, val isCompact: Boolean)

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
    val models: ImmutableList<ModelUi> = persistentListOf(),
    val permissions: ImmutableList<PermissionUi> = persistentListOf(),
    val isStopFailed: Boolean = false,
    val isStoppable: Boolean = true,
    val isResearchAvailable: Boolean = false,
    val isProjectAddingAvailable: Boolean = false,
    val isPickingProject: Boolean = false,
    val isProjectFailed: Boolean = false,
    val calendar: StudioCalendar = StudioCalendar(),
    val contextUsage: ContextUsageUi? = null,
    val providerUsage: ProviderUsageUi? = null,
    val configuration: SessionConfigurationUi? = null,
) {
    /** Only the settings of this pane wait while its session configuration is being confirmed. */
    val isSettingPending: Boolean get() = configuration?.pendingOperation != null
}

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
    val startedAt = runStartedAt[pane.sessionId]
    val configuration = configurations[pane.sessionId]
    val effectiveSettings = paneSettings(session, configuration)
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
        settings = effectiveSettings,
        configuration = configuration,
        isStopFailed = pane.sessionId in stopFailures,
        isStoppable = pane.sessionId !in uncancellable,
        isResearchAvailable = isResearchEnabled && pane.sessionId == null && pane.projectId == null &&
            models.any { it.id == settings.modelId && it.isResearchSupported },
        models = modelsForProject(
            pane.projectId ?: session?.projectId,
            configuration?.modelId ?: session?.modelId?.takeIf(String::isNotBlank),
        ),
        isProjectAddingAvailable = isProjectAddingAvailable && addingProjectTo == null,
        isPickingProject = addingProjectTo == pane.id,
        isProjectFailed = projectErrorPane == pane.id,
        permissions = permissions.filter { it.sessionId == pane.sessionId }.toImmutableList(),
        calendar = studioCalendar(now),
        contextUsage = contexts[pane.sessionId],
        providerUsage = providerUsage[effectiveSettings.modelId],
    )
}

private fun AiStudioScreenState.paneSettings(session: SessionUi?, configuration: SessionConfigurationUi?): SettingsUi =
    if (configuration != null) {
        settings.copy(
            modelId = configuration.modelId,
            approval = configuration.approval,
            nativeEffort = configuration.reasoningEffort,
        )
    } else {
        settings.copy(modelId = session?.modelId?.takeIf(String::isNotBlank) ?: settings.modelId)
    }

/**
 * Existing project chats keep their engine and connection, but may switch between that connection's models:
 * the runtime session changes the model in place and refuses any other route.
 */
private fun AiStudioScreenState.modelsForProject(projectId: String?, modelId: String?): ImmutableList<ModelUi> {
    val connection = models.firstOrNull { it.id == modelId }?.connectionKey
    return models.filter {
        val isSameConnection = connection != null && it.connectionKey == connection
        projectId == null || (it.isLocalProjectSupported && (modelId == null || it.id == modelId || isSameConnection))
    }.toImmutableList()
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
