package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AttachmentPreviewUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AttachmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.CardFocusRequestUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ComposerCaretRequestUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ContextUsageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EnginePreparationUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EngineSwitchUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.HarnessChoiceUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.HarnessChoicesUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ModelUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.OrganismUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PRIMARY_SUB_SESSION
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PermissionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanImplementationModeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ProjectTrustNoticeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ProviderUsageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.RenameUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SecurityLevelUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionConfigurationUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionTreeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SettingsUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SidebarUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioAppUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioNoticeDismissalUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioNoticeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.WorktreeJournalUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.WorktreeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.appFor
import io.aequicor.heartbeat.feature.aistudio.screenmodel.attachmentSupport
import io.aequicor.heartbeat.feature.aistudio.screenmodel.availableHarnesses
import io.aequicor.heartbeat.feature.aistudio.screenmodel.collapsed
import io.aequicor.heartbeat.feature.aistudio.screenmodel.contextPanelFor
import io.aequicor.heartbeat.feature.aistudio.screenmodel.dismissedNotices
import io.aequicor.heartbeat.feature.aistudio.screenmodel.isAttachmentPending
import io.aequicor.heartbeat.feature.aistudio.screenmodel.isHarnessSelectionReady
import io.aequicor.heartbeat.feature.aistudio.screenmodel.noticeDismissal
import io.aequicor.heartbeat.feature.aistudio.screenmodel.selectedHarnesses
import io.aequicor.heartbeat.feature.aistudio.screenmodel.selectedNative
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
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
    /** Interface slots and the single app host keyed by the shared composer/start/context/app helpers. */
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
    /** Targets captured with this pane's snapshot, including for delayed remote clicks. */
    val noticeTargets: ImmutableMap<StudioNoticeUi, StudioNoticeDismissalUi>,
    val models: ImmutableList<ModelUi> = persistentListOf(),
    /** The profile choice shown in the composer of ordinary native chats. */
    val securityLevel: SecurityLevelUi = SecurityLevelUi.Automatic,
    val enginePreparations: ImmutableList<EnginePreparationUi> = persistentListOf(),
    /** The trust decision the project of this (focused) chat waits for. */
    val projectTrust: ProjectTrustNoticeUi? = null,
    val dismissedNotices: ImmutableSet<StudioNoticeUi> = persistentSetOf(),
    val cardFocus: CardFocusRequestUi? = null,
    val app: StudioAppUi? = null,
    val isContextPanelAvailable: Boolean = false,
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
    val isSandboxAvailable: Boolean = false,
    val worktree: WorktreeUi? = null,
    val worktreeJournal: WorktreeJournalUi = WorktreeJournalUi.Ready,
    val configuration: SessionConfigurationUi? = null,
    val engineSwitch: EngineSwitchUi? = null,
    val attachments: ImmutableList<AttachmentUi> = persistentListOf(),
    val isAttachmentsEnabled: Boolean = false,
    val isRememberEnabled: Boolean = false,
    val isCompactEnabled: Boolean = false,
    /** The chat's context is being compacted now: the indicator animates. */
    val isCompacting: Boolean = false,
    val isOrganismEnabled: Boolean = false,
    val harnesses: ImmutableList<HarnessChoiceUi> = persistentListOf(),
    val isHarnessSelectionReady: Boolean = true,
    val isHarnessLoadFailed: Boolean = false,
    val isHarnessFailed: Boolean = false,
    val organism: OrganismUi? = null,
    val planning: PanePlanning = PanePlanning(),
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
    val isCompactReady: Boolean get() = isCompactOffered && session?.isRunning != true &&
        engineSwitch?.isPending != true

    /** A descendant view has no execution controls; root ownership remains unchanged. */
    val isNativeChild: Boolean get() = nativeTree?.sessions?.any {
        it.key == subSession &&
            it.kind == io.aequicor.heartbeat.feature.aistudio.screenmodel.SubSessionKindUi.Agent
    } == true

    /** Only the settings of this pane wait while its session configuration is being confirmed. */
    val isSettingPending: Boolean get() = configuration?.pendingOperation != null || engineSwitch?.isPending == true
}

/**
 * Planning mode of a pane: whether it is offered, the [plan] of the shown chat while it plans or implements it,
 * and the [modes] the plan can be implemented in.
 */
@Immutable
data class PanePlanning(
    val isEnabled: Boolean = false,
    val plan: PlanUi? = null,
    val modes: ImmutableList<PlanImplementationModeUi> = persistentListOf(PlanImplementationModeUi.Chat),
)

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
 * Harness choices of a pane: a chat shows committed connections, a new chat its override or available defaults.
 */
fun AiStudioScreenState.harnessChoices(pane: PaneUi): ImmutableList<HarnessChoiceUi> {
    val selected = selectedHarnesses(pane)
    return availableHarnesses(pane).map { harness ->
        HarnessChoiceUi(harness.id, harness.title, harness.id in selected, harness.description)
    }.toImmutableList()
}

/** Whether the last harness connection change of [chat] was not confirmed. */
private fun HarnessChoicesUi.isFailed(chat: String?): Boolean = chat != null && chat in failedChats

/** Rename origin of the header of [paneId]. */
internal fun paneOrigin(paneId: Int): String = "pane:$paneId"

/** Everything pane [pane] draws, projected from the screen state. */
fun AiStudioScreenState.paneContent(pane: PaneUi): PaneContent = projectPaneContent(
    pane,
    displayedSession(pane.sessionId),
    configurations[pane.sessionId],
    organisms[pane.sessionId],
)

/** Projects a pane using its resolved chat and execution configuration. */
private fun AiStudioScreenState.projectPaneContent(
    pane: PaneUi,
    session: SessionUi?,
    configuration: SessionConfigurationUi?,
    organism: OrganismUi?,
): PaneContent = PaneContent(
    pane = pane,
    enginePreparations = if (pane.id == focusedPaneId) enginePreparations else persistentListOf(),
    projectTrust = projectTrust?.takeIf { pane.id == focusedPaneId },
    dismissedNotices = dismissedNotices(pane.id),
    noticeTargets = StudioNoticeUi.entries.associateWith { noticeDismissal(pane, it) }.toImmutableMap(),
    cardFocus = cardFocusRequests[pane.id]?.takeIf { it.chatId == pane.sessionId },
    app = appFor(pane),
    isContextPanelAvailable = contextPanelFor(pane) != null,
    session = session,
    project = project(pane.projectId ?: session?.projectId),
    projects = projects,
    transcript = transcriptOf(pane.sessionId, session)?.collapsed(feedCollapse),
    isFocused = pane.id == focusedPaneId,
    isStopping = session != null && session.id in stopping,
    elapsed = elapsed(session),
    draft = draft(pane.id),
    attachments = attachments(pane.id),
    isAttachmentsEnabled = isAttachmentsEnabled,
    isRememberEnabled = isRememberEnabled,
    isCompactEnabled = isCompactEnabled,
    isCompacting = pane.sessionId in compactingChats,
    isOrganismEnabled = isOrganismEnabled,
    harnesses = harnessChoices(pane),
    isHarnessSelectionReady = isHarnessSelectionReady(pane),
    isHarnessLoadFailed = harnessChoices.isLoadFailed,
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
    settings = paneSettings(session, configuration).forOrganism(organism),
    securityLevel = if (session?.isHelper == true) SecurityLevelUi.Automatic else securityLevel,
    configuration = configuration,
    engineSwitch = engineSwitches[pane.sessionId],
    isStopFailed = pane.sessionId in stopFailures,
    isStoppable = pane.sessionId !in uncancellable,
    isResearchAvailable = isResearchOffered(pane),
    models = modelsForPane(pane, session, configuration, organism),
    isProjectAddingAvailable = isProjectAddingAvailable && addingProjectTo == null,
    isPickingProject = addingProjectTo == pane.id,
    isProjectFailed = projectErrorPane == pane.id,
    permissions = permissions.filter { it.sessionId == pane.sessionId }.toImmutableList(),
    calendar = studioCalendar(now),
    contextUsage = contextOf(pane.sessionId, session),
    providerUsage = providerUsage[paneSettings(session, configuration).forOrganism(organism).modelId],
    isWorktreeAvailable = isWorktreeOffered(pane),
    isSandboxAvailable = isSandboxAvailable && isWorktreeOffered(pane),
    worktree = worktreeFor(pane.sessionId),
    worktreeJournal = worktreeJournal,
).copy(planning = PanePlanning(isPlanningEnabled, plans[pane.sessionId], planModes(pane, session)))

/** Where a plan of [session] can be implemented: a checkout needs a Git project, an organism a shared folder. */
private fun AiStudioScreenState.planModes(pane: PaneUi, session: SessionUi?): ImmutableList<PlanImplementationModeUi> {
    val project = pane.projectId ?: session?.projectId
    val isCheckoutOffered = isWorktreeAvailable && worktreeJournal == WorktreeJournalUi.Ready &&
        project in repositoryProjects && worktrees[session?.id] == null
    return listOfNotNull(
        PlanImplementationModeUi.Chat,
        PlanImplementationModeUi.Worktree.takeIf { isCheckoutOffered },
        PlanImplementationModeUi.Sandbox.takeIf { isCheckoutOffered && isSandboxAvailable },
        PlanImplementationModeUi.Organism.takeIf { isOrganismEnabled && worktrees[session?.id] == null },
    ).toImmutableList()
}

/** Elapsed time is shown only while the session is running. */
private fun AiStudioScreenState.elapsed(session: SessionUi?): Duration? = if (session?.isRunning == true) {
    runStartedAt[session.id]?.let { (now - it).coerceAtLeast(Duration.ZERO) }
} else {
    null
}

/** Research can start in a new detached chat when the selected model supports it. */
private fun AiStudioScreenState.isResearchOffered(pane: PaneUi): Boolean =
    isResearchEnabled && pane.sessionId == null && pane.projectId == null &&
        models.any { it.id == settings.modelId && it.isResearchSupported }

/** The worktree mode is offered for a Git repository project once the journal is restored. */
private fun AiStudioScreenState.isWorktreeOffered(pane: PaneUi): Boolean =
    isWorktreeAvailable && worktreeJournal == WorktreeJournalUi.Ready && pane.projectId in repositoryProjects

/** Context belongs to the selected organism cell or to the native session. */
private fun AiStudioScreenState.contextOf(sessionId: String?, session: SessionUi?): ContextUsageUi? =
    if (session?.isOrganism == true) organismObservation(sessionId)?.context else contexts[sessionId]

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
internal fun AiStudioScreenState.modelsForProject(projectId: String?, modelId: String?): ImmutableList<ModelUi> {
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

private fun AiStudioScreenState.worktreeFor(
    session: String?,
): io.aequicor.heartbeat.feature.aistudio.screenmodel.WorktreeUi? =
    session?.let { worktrees[it] ?: worktrees["organism.$it.${subSessions[it] ?: PRIMARY_SUB_SESSION}"] }
