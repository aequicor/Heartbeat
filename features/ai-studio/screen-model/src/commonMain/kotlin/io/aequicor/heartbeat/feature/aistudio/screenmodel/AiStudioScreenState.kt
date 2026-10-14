@file:UseSerializers(ImmutableListSerializer::class, ImmutableSetSerializer::class, ImmutableMapSerializer::class)

package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import pro.respawn.flowmvi.api.MVIState
import kotlin.time.Instant

/**
 * Studio screen. [panes], [focusedPaneId], [running], [stopping] and [settings] mirror the machine;
 * [projects], [sessions] and [transcripts] (of open sessions) mirror the repository; [drafts] and [sidebar]
 * are local input. [runStartedAt] mirrors execution start times; [now] ticks while a run is active.
 */
@Serializable
@SerialName("AiStudioScreenState")
@Immutable
data class AiStudioScreenState(
    /** Native app update offer; remote hosts never install it. */
    val appUpdate: AppUpdateUi? = null,
    val models: ImmutableList<ModelUi> = persistentListOf(),
    val isResearchEnabled: Boolean = false,
    val isProjectAddingAvailable: Boolean = false,
    val isWorktreeAvailable: Boolean = false,
    val worktrees: ImmutableMap<String, WorktreeUi> = persistentMapOf(),
    val worktreeJournal: WorktreeJournalUi = WorktreeJournalUi.Ready,
    val addingProjectTo: Int? = null,
    val projectErrorPane: Int? = null,
    val permissions: ImmutableList<PermissionUi> = persistentListOf(),
    val phase: StudioPhase = StudioPhase.Loading,
    val panes: ImmutableList<PaneUi> = persistentListOf(),
    val focusedPaneId: Int = 0,
    val running: ImmutableSet<String> = persistentSetOf(),
    val contexts: ImmutableMap<String, ContextUsageUi> = persistentMapOf(),
    val providerUsage: ImmutableMap<String, ProviderUsageUi> = persistentMapOf(),
    val runStartedAt: ImmutableMap<String, Instant> = persistentMapOf(),
    val stopping: ImmutableSet<String> = persistentSetOf(),
    val stopFailures: ImmutableSet<String> = persistentSetOf(),
    val uncancellable: ImmutableSet<String> = persistentSetOf(),
    val settings: SettingsUi = DefaultSettingsUi,
    val configurations: ImmutableMap<String, SessionConfigurationUi> = persistentMapOf(),
    val projects: ImmutableList<ProjectUi> = persistentListOf(),
    val sessions: ImmutableList<SessionUi> = persistentListOf(),
    val transcripts: ImmutableMap<String, ImmutableList<MessageUi>> = persistentMapOf(),
    val drafts: ImmutableMap<String, String> = persistentMapOf(),
    /** Caret position of each composer draft; drives the active autocomplete token. */
    val draftCarets: ImmutableMap<String, Int> = persistentMapOf(),
    /** One-shot editor caret requests tied to accepted replacement text. */
    val draftCaretRequests: ImmutableMap<String, ComposerCaretRequestUi> = persistentMapOf(),
    /** Suggestions of each composer draft's active token; absent hides the popup. */
    val composerSuggestions: ImmutableMap<String, ComposerSuggestionsUi> = persistentMapOf(),
    val draftAttachments: ImmutableMap<String, ImmutableList<AttachmentUi>> = persistentMapOf(),
    val submissions: ImmutableMap<String, SubmissionUi> = persistentMapOf(),
    /** Diagram messages awaiting native acceptance, independent of composer snapshots. */
    val diagramSubmissions: ImmutableMap<String, DiagramSubmissionUi> = persistentMapOf(),
    /** Pending requests to reveal an exact inline card; consumed only by its owning pane and sequence. */
    val cardFocusRequests: ImmutableMap<Int, CardFocusRequestUi> = persistentMapOf(),
    /** Canonical app launchers, resolved for each pane independently of lazy transcript cards. */
    val apps: ImmutableMap<Int, StudioAppUi> = persistentMapOf(),
    val diagramFocusRequests: ImmutableMap<Int, Long> = persistentMapOf(),
    val diagramDraftReasons: ImmutableMap<String, DiagramDraftReasonUi> = persistentMapOf(),
    val attachmentRequests: ImmutableMap<String, String> = persistentMapOf(),
    val visibleAttachmentPreviews: ImmutableMap<String, PreviewVisibilityUi> = persistentMapOf(),
    val attachmentPreviews: ImmutableMap<String, AttachmentPreviewUi> = persistentMapOf(),
    val isAttachmentsEnabled: Boolean = false,
    /** Whether the composer offers the `/remember` command. */
    val isRememberEnabled: Boolean = false,
    /** Whether the composer offers `/compact` and the context indicator its compaction action. */
    val isCompactEnabled: Boolean = false,
    /** How the feed collapses agent work. */
    val feedCollapse: FeedCollapseUi = FeedCollapseUi(),
    /** Whether a new chat may be started as an organic AI organism. */
    val isOrganismEnabled: Boolean = false,
    /** Harnesses offered in the "+" menu, chat connections and choices of new chats. */
    val harnessChoices: HarnessChoicesUi = HarnessChoicesUi(),
    /** Organisms of organism chats, by chat id. */
    val organisms: ImmutableMap<String, OrganismUi> = persistentMapOf(),
    /** The sub-session each organism chat shows, by chat id; the zygote when absent. */
    val subSessions: ImmutableMap<String, String> = persistentMapOf(),
    /** Live transcripts of the shown sub-sessions of open organism chats, by chat id. */
    val subTranscripts: ImmutableMap<String, ImmutableList<MessageUi>> = persistentMapOf(),
    /** Telemetry belongs to the selected cell or judge, never to the organism as a whole. */
    val subObservations: ImmutableMap<String, SubSessionObservationUi> = persistentMapOf(),
    val nativeTrees: ImmutableMap<String, SessionTreeUi> = persistentMapOf(),
    val nativeTranscripts: ImmutableMap<String, NativeTranscriptUi> = persistentMapOf(),
    val attachmentErrorPanes: ImmutableSet<Int> = persistentSetOf(),
    val failedPanes: ImmutableSet<Int> = persistentSetOf(),
    val sidebar: SidebarUi = SidebarUi(),
    val now: Instant = Instant.DISTANT_PAST,
    /** Sequence number of the last intent a remote view applied ([AiStudioScreenIntent.RemoteApplied]). */
    val remoteSeq: Long = 0,
    /** Child screens hosted by the remote studio; native navigation owns its own embeds. */
    val remoteEmbeds: StudioEmbedViews = StudioEmbedViews(),
) : MVIState {
    /** The session of [id] with its running flag. */
    fun session(id: String?): SessionUi? =
        sessions.firstOrNull { it.id == id }?.let { it.copy(isRunning = it.id in running) }

    /** The project of [id]. */
    fun project(id: String?): ProjectUi? = projects.firstOrNull { it.id == id }

    /** Composer text of [paneId]: a draft follows the session shown in the pane; a new-session page keeps its own. */
    fun draft(paneId: Int): String = drafts[draftKey(paneId)].orEmpty()

    /** Key of the draft pane [paneId] edits: its displayed submission, its chat or the pane itself. */
    fun draftKey(paneId: Int): String {
        val sessionId = panes.firstOrNull { it.id == paneId }?.sessionId
        return submissions.values.firstOrNull {
            it.paneId == paneId && it.isDisplayed
        }?.draftKey ?: (sessionId ?: "pane:$paneId")
    }

    /** Durable metadata of files in the transient composer draft. */
    fun attachments(paneId: Int): ImmutableList<AttachmentUi> = draftAttachments[draftKey(paneId)] ?: persistentListOf()

    /** Suggestions of the pane's active token; null while no token is being completed. */
    fun suggestions(paneId: Int): ComposerSuggestionsUi? = composerSuggestions[draftKey(paneId)]

    /** The caret of the pane's draft; end of text until the editor reports a position. */
    fun draftCaret(paneId: Int): Int = draftCarets[draftKey(paneId)] ?: draft(paneId).length

    /** Last accepted completion's caret request for the pane's editor. */
    fun caretRequest(paneId: Int): ComposerCaretRequestUi? = draftCaretRequests[draftKey(paneId)]
}
