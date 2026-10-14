package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AttachmentPreviewUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AttachmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.CardFocusRequestUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ComposerCaretRequestUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ContextUsageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.HarnessChoiceUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.HarnessChoicesUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ModelUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.OrganismUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PRIMARY_SUB_SESSION
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PermissionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ProviderUsageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.RenameUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionConfigurationUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionTreeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SettingsUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SidebarUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioNoticeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.WorktreeJournalUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.WorktreeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.attachmentSupport
import io.aequicor.heartbeat.feature.aistudio.screenmodel.availableHarnesses
import io.aequicor.heartbeat.feature.aistudio.screenmodel.collapsed
import io.aequicor.heartbeat.feature.aistudio.screenmodel.dismissedNotices
import io.aequicor.heartbeat.feature.aistudio.screenmodel.isAttachmentPending
import io.aequicor.heartbeat.feature.aistudio.screenmodel.selectedNative
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
data class StudioExits(
    val onBack: (() -> Unit)? = null,
    val onOpenToggles: (() -> Unit)? = null,
    val onOpenProfileSettings: (() -> Unit)? = null,
    val onOpenConnections: (() -> Unit)? = null,
    val onOpenResearch: ((String) -> Unit)? = null,
    val onOpenBrowser: (() -> Unit)? = null,
    val isBrowserOpen: Boolean = false,
    val onCloseChatArea: (() -> Unit)? = null,
    val questions: ImmutableMap<String, StudioEmbed> = persistentMapOf(),
    val checklists: ImmutableMap<String, StudioEmbed> = persistentMapOf(),
    /** Interface slots keyed by the shared composer/start slot helpers. */
    val slots: ImmutableMap<String, StudioEmbed> = persistentMapOf(),
    /** Cards of interfaces shown in replies, keyed by `studioCardKey(pane, cardId)`. */
    val cards: ImmutableMap<String, StudioEmbed> = persistentMapOf(),
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
data class PaneContent(
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
    val dismissedNotices: ImmutableSet<StudioNoticeUi> = persistentSetOf(),
    val cardFocus: CardFocusRequestUi? = null,
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
    val isCompactEnabled: Boolean = false,
    val isOrganismEnabled: Boolean = false,
    val harnesses: ImmutableList<HarnessChoiceUi> = persistentListOf(),
    val isHarnessFailed: Boolean = false,
    val organism: OrganismUi? = null,
    val subSession: String = PRIMARY_SUB_SESSION,
    val nativeTree: SessionTreeUi? = null,
    val isAttachmentFailed: Boolean = false,
    val isAttachmentPending: Boolean = false,
    val attachmentPreviews: ImmutableMap<String, AttachmentPreviewUi> = persistentMapOf(),
    /** Suggestions of the pane's active composer token; null hides the popup. */
    val suggestions: io.aequicor.heartbeat.feature.aistudio.screenmodel.ComposerSuggestionsUi? = null,
    val caretRequest: ComposerCaretRequestUi? = null,
    val diagram: DiagramActionsUi = DiagramActionsUi(),
    val host: StudioHostCapabilities = StudioHostCapabilities(),
) {
    /** The chat exists and compaction is enabled, so the pane offers it. */
    val isCompactOffered: Boolean get() = isCompactEnabled && pane.sessionId != null

    /** The chat is offered compaction and is idle, so its context can be compacted now. */
    val isCompactReady: Boolean get() = isCompactOffered && session?.isRunning != true

    /** A descendant view has no execution controls; root ownership remains unchanged. */
    val isNativeChild: Boolean get() = nativeTree?.sessions?.any {
        it.key == subSession &&
            it.kind == io.aequicor.heartbeat.feature.aistudio.screenmodel.SubSessionKindUi.Agent
    } == true

    /** Only the settings of this pane wait while its session configuration is being confirmed. */
    val isSettingPending: Boolean get() = configuration?.pendingOperation != null
}

/** Sidebar data only: transcripts and drafts do not recompose the session lists. */
@Immutable
data class SidebarInput(
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
 * Only explicit connections are selected, regardless of a harness's stored legacy scope.
 */
fun AiStudioScreenState.harnessChoices(pane: PaneUi): ImmutableList<HarnessChoiceUi> {
    val selected = if (pane.sessionId != null) harnessChoices.chats[pane.sessionId] else harnessChoices.panes[pane.id]
    return availableHarnesses(pane).map { harness ->
        HarnessChoiceUi(harness.id, harness.title, selected?.contains(harness.id) == true, harness.description)
    }.toImmutableList()
}

/** Whether the last harness connection change of [chat] was not confirmed. */
private fun HarnessChoicesUi.isFailed(chat: String?): Boolean = chat != null && chat in failedChats

/** Rename origin of the header of [paneId]. */
internal fun paneOrigin(paneId: Int): String = "pane:$paneId"

/** Everything pane [pane] draws, projected from the screen state. */
fun AiStudioScreenState.paneContent(pane: PaneUi): PaneContent {
    val session = displayedSession(pane.sessionId)
    val startedAt = runStartedAt[pane.sessionId]
    val configuration = configurations[pane.sessionId]
    val organism = organisms[pane.sessionId]
    val effectiveSettings = paneSettings(session, configuration).forOrganism(organism)
    return PaneContent(
        pane = pane,
        dismissedNotices = dismissedNotices(pane.id),
        cardFocus = cardFocusRequests[pane.id]?.takeIf { it.chatId == pane.sessionId },
        session = session,
        project = project(pane.projectId ?: session?.projectId),
        projects = projects,
        transcript = transcriptOf(pane.sessionId, session)?.collapsed(feedCollapse),
        isFocused = pane.id == focusedPaneId,
        isStopping = session != null && session.id in stopping,
        elapsed = if (session?.isRunning == true) startedAt?.let { (now - it).coerceAtLeast(Duration.ZERO) } else null,
        draft = draft(pane.id),
        attachments = attachments(pane.id),
        isAttachmentsEnabled = isAttachmentsEnabled,
        isRememberEnabled = isRememberEnabled,
        isCompactEnabled = isCompactEnabled,
        isOrganismEnabled = isOrganismEnabled,
        harnesses = harnessChoices(pane),
        isHarnessFailed = harnessChoices.isFailed(pane.sessionId),
        organism = organism,
        subSession = selectedSubSession(pane.sessionId, session),
        nativeTree = nativeTrees[pane.sessionId],
        isAttachmentFailed = pane.id in attachmentErrorPanes,
        isAttachmentPending = isAttachmentPending(pane.id),
        attachmentPreviews = attachmentPreviews,
        suggestions = composerSuggestions[draftKey(pane.id)]?.takeIf { it.items.isNotEmpty() },
        caretRequest = caretRequest(pane.id),
        diagram = diagramActions(pane),
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
        contextUsage = contextOf(pane.sessionId, session),
        providerUsage = providerUsage[effectiveSettings.modelId],
        isWorktreeAvailable = isWorktreeAvailable && worktreeJournal == WorktreeJournalUi.Ready,
        worktree = worktrees[pane.sessionId],
        worktreeJournal = worktreeJournal,
    )
}

/** Context belongs to the selected organism cell or to the native session. */
private fun AiStudioScreenState.contextOf(sessionId: String?, session: SessionUi?): ContextUsageUi? =
    if (session?.isOrganism == true) organismObservation(sessionId)?.context else contexts[sessionId]

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

        selectedNative(sessionId) != PRIMARY_SUB_SESSION -> nativeTranscripts[sessionId]
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

/** What the sidebar draws, projected from the screen state. */
fun AiStudioScreenState.sidebarInput(): SidebarInput {
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
    return if (selectedNative(id) == PRIMARY_SUB_SESSION) session else session?.copy(isContinuable = false)
}

/** Selection changes hide the previous cell's telemetry immediately, before the new observer emits. */
private fun AiStudioScreenState.organismObservation(id: String?) = subObservations[id]?.takeIf {
    selectedNative(id) == PRIMARY_SUB_SESSION && it.key == (subSessions[id] ?: PRIMARY_SUB_SESSION)
}

private fun AiStudioScreenState.selectedSubSession(id: String?, session: SessionUi?): String =
    if (session?.isOrganism == true) subSessions[id] ?: PRIMARY_SUB_SESSION else selectedNative(id)

/** Diagram interaction state of one pane, separate from normal composer submissions. */
@Immutable
data class DiagramActionsUi(
    val isPending: Boolean = false,
    val focusRequest: Long = 0L,
    val draftReason: io.aequicor.heartbeat.feature.aistudio.screenmodel.DiagramDraftReasonUi? = null,
)

private fun AiStudioScreenState.diagramActions(pane: PaneUi): DiagramActionsUi = DiagramActionsUi(
    isPending = diagramSubmissions.values.any { it.sessionId == pane.sessionId },
    focusRequest = diagramFocusRequests[pane.id] ?: 0L,
    draftReason = diagramDraftReasons[pane.sessionId],
)

/** The same pane with native affordances constrained by its rendering host. */
fun AiStudioScreenState.paneContent(pane: PaneUi, host: StudioHostCapabilities): PaneContent =
    paneContent(pane).let { content ->
        content.copy(
            host = host,
            isProjectAddingAvailable = content.isProjectAddingAvailable && host.isProjectAddingAvailable,
        )
    }
