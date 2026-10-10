package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AttachmentPreviewUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AttachmentUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ContextUsageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.HarnessChoiceUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ModelUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.OrganismUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PermissionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PrimarySubSession
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ProviderUsageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.RenameUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionConfigurationUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionTreeUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SettingsUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SidebarUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.WorktreeJournalUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.WorktreeUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.attachmentSupport
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.selectedNative
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
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
    val checklists: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
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
    val isWorktreeAvailable: Boolean = false,
    val worktree: WorktreeUi? = null,
    val worktreeJournal: WorktreeJournalUi = WorktreeJournalUi.Ready,
    val configuration: SessionConfigurationUi? = null,
    val attachments: ImmutableList<AttachmentUi> = persistentListOf(),
    val isAttachmentsEnabled: Boolean = false,
    val isRememberEnabled: Boolean = false,
    val isOrganismEnabled: Boolean = false,
    val harnesses: ImmutableList<HarnessChoiceUi> = persistentListOf(),
    val organism: OrganismUi? = null,
    val subSession: String = PrimarySubSession,
    val nativeTree: SessionTreeUi? = null,
    val isAttachmentFailed: Boolean = false,
    val attachmentPreviews: ImmutableMap<String, AttachmentPreviewUi> = persistentMapOf(),
) {
    /** A descendant view has no execution controls; root ownership remains unchanged. */
    val isNativeChild: Boolean get() = nativeTree?.sessions?.any {
        it.key == subSession &&
            it.kind == io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SubSessionKindUi.Agent
    } == true

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
    val awaitingPermission: ImmutableSet<String> = persistentSetOf(),
)

/**
 * Harness choices of a pane: a chat shows its committed connections, a new chat the choice kept on its pane.
 * Harnesses active by profile or project scope are shown selected and cannot be toggled.
 */
internal fun AiStudioScreenState.harnessChoices(pane: PaneUi, projectId: String?): ImmutableList<HarnessChoiceUi> {
    val selected = if (pane.sessionId != null) harnessChoices.chats[pane.sessionId] else harnessChoices.panes[pane.id]
    return harnessChoices.options.map { harness ->
        val isPermanent = harness.isProfile || (projectId != null && projectId in harness.projects)
        HarnessChoiceUi(harness.id, harness.title, isPermanent || selected?.contains(harness.id) == true, isPermanent)
    }.toImmutableList()
}

/** Rename origin of the header of [paneId]. */
internal fun paneOrigin(paneId: Int): String = "pane:$paneId"

internal fun AiStudioScreenState.paneContent(pane: PaneUi): PaneContent {
    val session = displayedSession(pane.sessionId)
    val startedAt = runStartedAt[pane.sessionId]
    val configuration = configurations[pane.sessionId]
    val organism = organisms[pane.sessionId]
    val effectiveSettings = paneSettings(session, configuration).forOrganism(organism)
    return PaneContent(
        pane = pane,
        session = session,
        project = project(pane.projectId ?: session?.projectId),
        projects = projects,
        transcript = transcriptOf(pane.sessionId, session),
        isFocused = pane.id == focusedPaneId,
        isStopping = session != null && session.id in stopping,
        elapsed = if (session?.isRunning == true) startedAt?.let { (now - it).coerceAtLeast(Duration.ZERO) } else null,
        draft = draft(pane.id),
        attachments = attachments(pane.id),
        isAttachmentsEnabled = isAttachmentsEnabled,
        isRememberEnabled = isRememberEnabled,
        isOrganismEnabled = isOrganismEnabled,
        harnesses = harnessChoices(pane, session?.projectId ?: pane.projectId),
        organism = organism,
        subSession = selectedSubSession(pane.sessionId, session),
        nativeTree = nativeTrees[pane.sessionId],
        isAttachmentFailed = pane.id in attachmentErrorPanes,
        attachmentPreviews = attachmentPreviews,
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
            paneModelId(session, configuration, organism),
        ),
        isProjectAddingAvailable = isProjectAddingAvailable && addingProjectTo == null,
        isPickingProject = addingProjectTo == pane.id,
        isProjectFailed = projectErrorPane == pane.id,
        permissions = permissions.filter { it.sessionId == pane.sessionId }.toImmutableList(),
        calendar = studioCalendar(now),
        contextUsage = if (session?.isOrganism == true) {
            organismObservation(pane.sessionId)?.context
        } else {
            contexts[pane.sessionId]
        },
        providerUsage = providerUsage[effectiveSettings.modelId],
        isWorktreeAvailable = isWorktreeAvailable && worktreeJournal == WorktreeJournalUi.Ready,
        worktree = worktrees[pane.sessionId],
        worktreeJournal = worktreeJournal,
    )
}

private fun paneModelId(session: SessionUi?, configuration: SessionConfigurationUi?, organism: OrganismUi?): String? =
    organism?.modelId ?: configuration?.modelId ?: session?.modelId?.takeIf(String::isNotBlank)

/** Existing organisms keep their execution settings when the defaults for new chats change. */
private fun SettingsUi.forOrganism(organism: OrganismUi?): SettingsUi = if (organism == null) {
    this
} else {
    copy(modelId = organism.modelId ?: modelId, approval = organism.approval ?: approval)
}

/** An organism chat shows the live transcript of its chosen sub-session instead of a stored one. */
private fun AiStudioScreenState.transcriptOf(sessionId: String?, session: SessionUi?): ImmutableList<MessageUi>? =
    when {
        sessionId == null -> null

        selectedNative(sessionId) != PrimarySubSession -> nativeTranscripts[sessionId]
            ?.takeIf { it.key == selectedNative(sessionId) }?.messages ?: persistentListOf()

        session?.isOrganism == true -> subTranscripts[sessionId] ?: persistentListOf()

        else -> transcripts[sessionId]
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

/**
 * Gate of the screen-level native capture: dropping files and pasting a clipboard screenshot is allowed exactly
 * where the store would import them, so a captured gesture is never silently rejected for the focused pane.
 */
internal fun AiStudioScreenState.canCaptureAttachments(paneId: Int): Boolean {
    val pane = panes.firstOrNull { it.id == paneId } ?: return false
    if (!isAttachmentsEnabled || pane.isCreating) return false
    if (session(pane.sessionId)?.isRunning == true) return false
    return attachmentSupport(paneId)?.mediaTypes?.isNotEmpty() == true
}

internal fun AiStudioScreenState.sidebarInput(): SidebarInput {
    val focused = panes.firstOrNull { it.id == focusedPaneId }
    return SidebarInput(
        projects = projects,
        sessions = sessions,
        awaitingPermission = permissions.map { it.sessionId }.toImmutableSet(),
        running = running,
        sidebar = sidebar,
        selectedId = focused?.sessionId,
        newSessionProjectId = focused?.projectId
            ?: session(focused?.sessionId)?.projectId
            ?: projects.firstOrNull()?.id,
    )
}

private fun AiStudioScreenState.displayedSession(id: String?): SessionUi? {
    val session = session(id)?.let { current ->
        val isRunning = organismObservation(id)?.isRunning
        if (current.isOrganism && isRunning != null) current.copy(isRunning = isRunning) else current
    }
    return if (selectedNative(id) == PrimarySubSession) session else session?.copy(isContinuable = false)
}

/** Selection changes hide the previous cell's telemetry immediately, before the new observer emits. */
private fun AiStudioScreenState.organismObservation(id: String?) = subObservations[id]?.takeIf {
    selectedNative(id) == PrimarySubSession && it.key == (subSessions[id] ?: PrimarySubSession)
}

private fun AiStudioScreenState.selectedSubSession(id: String?, session: SessionUi?): String =
    if (session?.isOrganism == true) subSessions[id] ?: PrimarySubSession else selectedNative(id)
