@file:UseSerializers(ImmutableListSerializer::class, ImmutableSetSerializer::class, ImmutableMapSerializer::class)

package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.screenmodel.ApprovalUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AttachmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EffortUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ImmutableListSerializer
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ImmutableMapSerializer
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ImmutableSetSerializer
import io.aequicor.heartbeat.feature.aistudio.screenmodel.NativeAttachmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.OrganismActionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SidebarMode
import io.aequicor.heartbeat.feature.aistudio.screenmodel.WorktreeActionUi
import io.aequicor.heartbeat.feature.checklist.screenmodel.ChecklistScreenIntent
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenIntent
import kotlinx.collections.immutable.ImmutableList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent

/** User events of the studio screen. */
@Serializable
@SerialName("AiStudioScreenIntent")
sealed interface AiStudioScreenIntent : MVIIntent {
    /** An input addressed to one particular child handle of the remote view. */
    @Serializable
    sealed interface Embed : AiStudioScreenIntent

    /** Input to the question source's current handle. */
    @Serializable
    data class QuestionInput(val source: String, val instance: String, val input: QuestionnaireScreenIntent) : Embed

    /** Input to a persisted checklist's current handle. */
    @Serializable
    data class ChecklistInput(val id: String, val instance: String, val input: ChecklistScreenIntent) : Embed

    /** Changes what the panes show; forwarded to the machine. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Navigation")
    sealed interface Navigation : AiStudioScreenIntent

    /** Explicit reply to an engine permission request. */
    @Serializable
    @SerialName("AiStudioScreenIntent.RespondPermission")
    data class RespondPermission(val sessionId: String, val requestId: String, val optionId: String) : Composer

    /** Composer input, runs and model preferences. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Composer")
    sealed interface Composer : AiStudioScreenIntent

    /** Connects a harness to the chat of [paneId], or remembers the choice for a new chat. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SelectHarness")
    data class SelectHarness(val paneId: Int, val harness: String, val isSelected: Boolean) : AiStudioScreenIntent

    /** Organic AI chats: the mode of a new chat, the shown sub-session, control and the user's decisions. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Organism")
    sealed interface Organism : AiStudioScreenIntent

    /** Switches the organic AI mode of a new chat in [paneId]. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SelectOrganism")
    data class SelectOrganism(val paneId: Int, val isEnabled: Boolean) : Organism

    /** Shows the sub-session [key] (a cell id or a case id) of the organism chat [sessionId]. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SelectSubSession")
    data class SelectSubSession(val sessionId: String, val key: String) : Organism

    /** Aborts the organism of [sessionId] or resumes its stalled zygote. */
    @Serializable
    @SerialName("AiStudioScreenIntent.ControlOrganism")
    data class ControlOrganism(val sessionId: String, val action: OrganismActionUi) : Organism

    /** Answers the permission request [requestId] of the [turn] of [cell] in the organism of [sessionId]. */
    @Serializable
    @SerialName("AiStudioScreenIntent.DecideOrganism")
    data class DecideOrganism(
        val sessionId: String,
        val cell: String,
        val turn: String,
        val requestId: String,
        val optionId: String,
    ) : Organism

    /** Local attachment input and saved-file actions. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Attachment")
    sealed interface Attachment : Composer

    /** Worktree execution mode and the persisted task's completion/build controls. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Worktree")
    sealed interface Worktree : Composer

    /** Metadata changes of one session. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SessionAction")
    sealed interface SessionAction : AiStudioScreenIntent

    /**
     * Marks the intents of a remote view up to [seq] as applied. The store applies intents in order, so the state
     * after this marker reflects all of them; the view acknowledges them with that state.
     */
    @Serializable
    @SerialName("AiStudioScreenIntent.RemoteApplied")
    data class RemoteApplied(val seq: Long) : AiStudioScreenIntent

    /** Local sidebar presentation. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Sidebar")
    sealed interface Sidebar : AiStudioScreenIntent

    /** Prepares the workspace again after a failure. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Retry")
    data object Retry : Navigation

    /** Opens the new-session page for [projectId] in the focused pane. */
    @Serializable
    @SerialName("AiStudioScreenIntent.NewSession")
    data class NewSession(val projectId: String?) : Navigation

    /** Changes the project of a new session. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SelectProject")
    data class SelectProject(val paneId: Int, val projectId: String?) : Navigation

    /** Chooses and registers a local project for a new chat. */
    @Serializable
    @SerialName("AiStudioScreenIntent.AddProject")
    data class AddProject(val paneId: Int) : Navigation

    /** Shows a session in the focused pane. */
    @Serializable
    @SerialName("AiStudioScreenIntent.OpenSession")
    data class OpenSession(val sessionId: String) : Navigation

    /** Shows a session (or a new-session page) next to the focused pane. */
    @Serializable
    @SerialName("AiStudioScreenIntent.OpenBeside")
    data class OpenBeside(val sessionId: String?) : Navigation

    /** Closes one of several panes. */
    @Serializable
    @SerialName("AiStudioScreenIntent.ClosePane")
    data class ClosePane(val paneId: Int) : Navigation

    /** Moves the composer focus to another pane. */
    @Serializable
    @SerialName("AiStudioScreenIntent.FocusPane")
    data class FocusPane(val paneId: Int) : Navigation

    /** Composer autocomplete: draft and caret changes, navigation, acceptance and dismissal. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Suggestions")
    sealed interface Suggestions : Composer {
        /** The composer text of a pane changed. */
        @Serializable
        @SerialName("AiStudioScreenIntent.Suggestions.DraftChanged")
        data class DraftChanged(val paneId: Int, val text: String) : Suggestions

        /** The caret of a pane's composer moved; the active token is resolved against it. */
        @Serializable
        @SerialName("AiStudioScreenIntent.Suggestions.CaretMoved")
        data class CaretMoved(val paneId: Int, val caret: Int) : Suggestions

        /** The editor applied this one-shot caret request; remounting must not replay it. */
        @Serializable
        @SerialName("AiStudioScreenIntent.Suggestions.CaretRequestApplied")
        data class CaretRequestApplied(val paneId: Int, val id: Long) : Suggestions

        /** Keyboard navigation moved the suggestion selection by [delta] within the pane's list. */
        @Serializable
        @SerialName("AiStudioScreenIntent.Suggestions.MoveSuggestion")
        data class MoveSuggestion(val paneId: Int, val delta: Int) : Suggestions

        /** Takes the suggestion at [index] of the pane's list, or the selected one when null. */
        @Serializable
        @SerialName("AiStudioScreenIntent.Suggestions.AcceptSuggestion")
        data class AcceptSuggestion(val paneId: Int, val index: Int? = null) : Suggestions

        /** Hides the pane's suggestion list until the active token changes. */
        @Serializable
        @SerialName("AiStudioScreenIntent.Suggestions.DismissSuggestions")
        data class DismissSuggestions(val paneId: Int) : Suggestions
    }

    /** Adds files using a lifecycle-owned native picker. */
    @Serializable
    @SerialName("AiStudioScreenIntent.PickAttachments")
    data class PickAttachments(val paneId: Int) : Attachment

    /** Removes a file from the transient draft without deleting its durable copy. */
    @Serializable
    @SerialName("AiStudioScreenIntent.RemoveAttachment")
    data class RemoveAttachment(val paneId: Int, val id: String) : Attachment

    /** Opens a saved file for preview and export, including with adding switched off. */
    @Serializable
    @SerialName("AiStudioScreenIntent.OpenAttachment")
    data class OpenAttachment(val id: String) : Attachment

    /** Saves original bytes through the saved attachment's native export route. */
    @Serializable
    @SerialName("AiStudioScreenIntent.ExportAttachment")
    data class ExportAttachment(val id: String) : Attachment

    /** Imports explicitly captured clipboard/drop inputs. */
    @Serializable
    @SerialName("AiStudioScreenIntent.ImportAttachments")
    data class ImportAttachments(val paneId: Int, val inputs: List<NativeAttachmentUi>) : Attachment {
        override fun toString(): String = "ImportAttachments(paneId=$paneId, count=${inputs.size})"
    }

    /** Correlated picker result delivered by the navigation component. */
    @Serializable
    @SerialName("AiStudioScreenIntent.AttachmentsSelected")
    data class AttachmentsSelected(val requestId: String, val files: ImmutableList<AttachmentUi>) : Attachment

    /** Composed attachment rows request their small preview and release it on disposal. */
    @Serializable
    @SerialName("AiStudioScreenIntent.AttachmentPreviewVisible")
    data class AttachmentPreviewVisible(val id: String, val mediaType: String, val isVisible: Boolean) : Attachment

    /** Fixes the execution mode of a new pane before its first submission. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SelectWorktree")
    data class SelectWorktree(val paneId: Int, val isEnabled: Boolean) : Worktree

    /** Applies one explicit decision to the saved result. */
    @Serializable
    @SerialName("AiStudioScreenIntent.DecideWorktree")
    data class DecideWorktree(val sessionId: String, val action: WorktreeActionUi) : Worktree

    /** Reconciles interrupted operations against the current checkout. */
    @Serializable
    @SerialName("AiStudioScreenIntent.RecheckWorktree")
    data class RecheckWorktree(val sessionId: String) : Worktree

    /** Restores the profile journal after a storage failure. */
    @Serializable
    @SerialName("AiStudioScreenIntent.RetryWorktreeJournal")
    data object RetryWorktreeJournal : Worktree

    /** Cancels one queued or running coordinated build. */
    @Serializable
    @SerialName("AiStudioScreenIntent.CancelWorktreeBuild")
    data class CancelWorktreeBuild(val sessionId: String, val operation: String) : Worktree

    /** A user clicked an action in the editable root transcript of this session. */
    @Serializable
    @SerialName("AiStudioScreenIntent.DiagramAction")
    data class DiagramAction(val sessionId: String, val text: String, val isSend: Boolean) : AiStudioScreenIntent {
        override fun toString(): String = "DiagramAction(sessionId=$sessionId, isSend=$isSend)"
    }

    /** Sends the composer text of a pane. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Submit")
    data class Submit(val paneId: Int) : Composer

    /** Stops the running agent of a session. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Stop")
    data class Stop(val sessionId: String) : Composer

    /** Actions of the context and limits indicator. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Usage")
    sealed interface Usage : Composer

    /** Requests provider telemetry when the details panel opens. */
    @Serializable
    @SerialName("AiStudioScreenIntent.RefreshUsage")
    data class RefreshUsage(val modelId: String) : Usage

    /** Compacts the context of the pane's chat once it is idle. */
    @Serializable
    @SerialName("AiStudioScreenIntent.CompactContext")
    data class CompactContext(val paneId: Int) : Usage

    /** Changes an existing session's model, or the default for a new page. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SelectModel")
    data class SelectModel(val modelId: String, val paneId: Int? = null) : Composer

    /** Changes the session's effort for subsequent requests, or the default for a new page. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SelectEffort")
    data class SelectEffort(val effort: EffortUi, val paneId: Int? = null) : Composer

    /** Selects an advertised native effort for an exact model route; null restores the engine default. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SelectEngineEffort")
    data class SelectEngineEffort(val modelId: String, val effort: String?, val paneId: Int? = null) : Composer

    /** Chooses how the agent treats actions with side effects. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SelectApproval")
    data class SelectApproval(val approval: ApprovalUi, val paneId: Int? = null) : Composer

    /** Pins or unpins a session. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SetPinned")
    data class SetPinned(val sessionId: String, val isPinned: Boolean) : SessionAction

    /** Marks a session as read or unread. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SetUnread")
    data class SetUnread(val sessionId: String, val isUnread: Boolean) : SessionAction

    /** Archives or restores a session. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SetArchived")
    data class SetArchived(val sessionId: String, val isArchived: Boolean) : SessionAction

    /** Starts editing the title of a session in the sidebar. */
    @Serializable
    @SerialName("AiStudioScreenIntent.StartRename")
    data class StartRename(val sessionId: String, val origin: String) : SessionAction

    /** The edited title changed. */
    @Serializable
    @SerialName("AiStudioScreenIntent.RenameChanged")
    data class RenameChanged(val title: String) : SessionAction

    /** Saves the edited title. */
    @Serializable
    @SerialName("AiStudioScreenIntent.CommitRename")
    data object CommitRename : SessionAction

    /** Discards the edited title. */
    @Serializable
    @SerialName("AiStudioScreenIntent.CancelRename")
    data object CancelRename : SessionAction

    /** Switches between active and archived sessions. */
    @Serializable
    @SerialName("AiStudioScreenIntent.ShowSidebarMode")
    data class ShowSidebarMode(val mode: SidebarMode) : Sidebar

    /** Shows or hides the sidebar search field; hiding it clears the query. */
    @Serializable
    @SerialName("AiStudioScreenIntent.ToggleSearch")
    data object ToggleSearch : Sidebar

    /** The sidebar search query changed. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SearchChanged")
    data class SearchChanged(val query: String) : Sidebar

    /** Expands or collapses the sessions of a project. */
    @Serializable
    @SerialName("AiStudioScreenIntent.ToggleProject")
    data class ToggleProject(val projectId: String) : Sidebar

    /** Expands or collapses the projects section. */
    @Serializable
    @SerialName("AiStudioScreenIntent.ToggleProjectsSection")
    data object ToggleProjectsSection : Sidebar

    /** Expands or collapses the recent sessions section. */
    @Serializable
    @SerialName("AiStudioScreenIntent.ToggleRecentSection")
    data object ToggleRecentSection : Sidebar

    /** Shows or hides the sidebar on wide windows. */
    @Serializable
    @SerialName("AiStudioScreenIntent.ToggleSidebar")
    data object ToggleSidebar : Sidebar

    /** Opens or closes the sidebar drawer on compact windows. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SetDrawerOpen")
    data class SetDrawerOpen(val isOpen: Boolean) : Sidebar
}

/** No one-shot screen actions: every reaction is state. */
@Serializable
@SerialName("AiStudioScreenAction")
sealed interface AiStudioScreenAction : MVIAction
