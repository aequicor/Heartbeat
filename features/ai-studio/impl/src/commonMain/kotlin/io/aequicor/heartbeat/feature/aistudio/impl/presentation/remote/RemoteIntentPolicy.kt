package io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote

import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.NativeAttachmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.OrganismActionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.isValid

/**
 * Whether a remote view may send [intent]. Refused: native desktop surfaces the browser cannot show (folder and file
 * pickers, opening or exporting an attachment, file paths of this computer, panes side by side) and the view's own
 * acknowledgement marker, which only the view session sends.
 */
internal fun isAllowedRemotely(intent: AiStudioScreenIntent): Boolean = when (intent) {
    AiStudioScreenIntent.InstallAppUpdate,
    is AiStudioScreenIntent.AddProject,
    is AiStudioScreenIntent.OpenBeside,
    is AiStudioScreenIntent.RemoteApplied,
    is AiStudioScreenIntent.CardFocusApplied,
    is AiStudioScreenIntent.OpenApp,
    is AiStudioScreenIntent.SelectOrganism,
    -> false

    is AiStudioScreenIntent.ControlOrganism -> intent.action == OrganismActionUi.Abort

    is AiStudioScreenIntent.Attachment -> isAllowedAttachment(intent)

    is AiStudioScreenIntent.Suggestions -> isAllowedSuggestion(intent)

    is AiStudioScreenIntent.Plan -> isAllowedPlan(intent)

    is AiStudioScreenIntent.QuestionInput,
    is AiStudioScreenIntent.ChecklistInput,
    is AiStudioScreenIntent.RespondPermission,
    is AiStudioScreenIntent.SelectHarness,
    AiStudioScreenIntent.ReloadHarnesses,
    is AiStudioScreenIntent.DismissNotice, is AiStudioScreenIntent.DecideProjectTrust,
    is AiStudioScreenIntent.SelectSubSession,
    is AiStudioScreenIntent.DecideOrganism,
    is AiStudioScreenIntent.Retry,
    is AiStudioScreenIntent.NewSession,
    is AiStudioScreenIntent.SelectProject,
    is AiStudioScreenIntent.OpenSession,
    is AiStudioScreenIntent.ClosePane,
    is AiStudioScreenIntent.FocusPane,
    is AiStudioScreenIntent.SelectWorktree,
    is AiStudioScreenIntent.DecideWorktree,
    is AiStudioScreenIntent.RecheckWorktree,
    is AiStudioScreenIntent.RetryWorktreeJournal,
    is AiStudioScreenIntent.CancelWorktreeBuild,
    is AiStudioScreenIntent.DiagramAction,
    is AiStudioScreenIntent.Submit,
    is AiStudioScreenIntent.Stop,
    is AiStudioScreenIntent.RefreshUsage,
    is AiStudioScreenIntent.CompactContext,
    is AiStudioScreenIntent.SelectModel,
    is AiStudioScreenIntent.SelectEffort,
    is AiStudioScreenIntent.SelectEngineEffort,
    is AiStudioScreenIntent.SelectApproval,
    is AiStudioScreenIntent.SetPinned,
    is AiStudioScreenIntent.SetUnread,
    is AiStudioScreenIntent.SetArchived,
    is AiStudioScreenIntent.StartRename,
    is AiStudioScreenIntent.RenameChanged,
    is AiStudioScreenIntent.CommitRename,
    is AiStudioScreenIntent.CancelRename,
    is AiStudioScreenIntent.ShowSidebarMode,
    is AiStudioScreenIntent.ToggleSearch, is AiStudioScreenIntent.SearchChanged,
    is AiStudioScreenIntent.ToggleProject, is AiStudioScreenIntent.ToggleProjectSessions,
    is AiStudioScreenIntent.ToggleProjectsSection,
    is AiStudioScreenIntent.ToggleRecentSection, is AiStudioScreenIntent.ToggleNoProjectSection,
    is AiStudioScreenIntent.ToggleSidebar,
    is AiStudioScreenIntent.SetDrawerOpen,
    -> true
}

/** Remote panels plan like the window: every plan intent is listed, so a new one must be allowed explicitly. */
private fun isAllowedPlan(intent: AiStudioScreenIntent.Plan): Boolean = when (intent) {
    is AiStudioScreenIntent.SelectPlanning,
    is AiStudioScreenIntent.SetPlanning,
    is AiStudioScreenIntent.ApprovePlan,
    is AiStudioScreenIntent.SavePlan,
    is AiStudioScreenIntent.ImplementPlan,
    -> true
}

/** Remote input may edit the draft and navigate its suggestions; new suggestion actions stay explicit. */
private fun isAllowedSuggestion(intent: AiStudioScreenIntent.Suggestions): Boolean = when (intent) {
    is AiStudioScreenIntent.Suggestions.DraftChanged,
    is AiStudioScreenIntent.Suggestions.CaretMoved,
    is AiStudioScreenIntent.Suggestions.CaretRequestApplied,
    is AiStudioScreenIntent.Suggestions.MoveSuggestion,
    is AiStudioScreenIntent.Suggestions.AcceptSuggestion,
    is AiStudioScreenIntent.Suggestions.DismissSuggestions,
    -> true
}

/** Keep new attachment surfaces explicit instead of inheriting permission from the composer group. */
private fun isAllowedAttachment(intent: AiStudioScreenIntent.Attachment): Boolean = when (intent) {
    is AiStudioScreenIntent.PickAttachments,
    is AiStudioScreenIntent.AttachmentsSelected,
    is AiStudioScreenIntent.OpenAttachment,
    is AiStudioScreenIntent.ExportAttachment,
    -> false

    is AiStudioScreenIntent.ImportAttachments -> intent.inputs.all {
        when (it) {
            is NativeAttachmentUi.File -> false
            is NativeAttachmentUi.Page -> it.isValid()
            is NativeAttachmentUi.Image -> true
        }
    }

    is AiStudioScreenIntent.RemoveAttachment, is AiStudioScreenIntent.AttachmentPreviewVisible -> true
}
