package io.aequicor.heartbeat.feature.agentlearning.impl.presentation

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState

/** How new instructions are accepted, as shown in settings. */
internal enum class ApprovalUi { Ask, Automatic, AcceptAll }

/** Where an instruction applies, as shown in settings. */
internal enum class KindUi { General, Model, Skill }

/** Which instructions the list shows. */
internal sealed interface ProjectFilterUi {
    /** Every instruction. */
    data object All : ProjectFilterUi

    /** Instructions of chats without a project. */
    data object WithoutProject : ProjectFilterUi

    /** Instructions of one saved project. */
    data class Project(val key: String) : ProjectFilterUi
}

/** A saved project offered by the filter. */
internal data class ProjectUi(val key: String, val name: String)

/** One learned instruction; [projectKey] is null for chats without a project, [model] names an engine or model. */
internal data class InstructionUi(
    val id: String,
    val kind: KindUi,
    val title: String,
    val description: String,
    val content: String,
    val isEnabled: Boolean,
    val projectKey: String?,
    val model: String?,
)

/** Texts being edited with their length limits; [kind] decides which fields are shown. */
internal data class DraftUi(
    val id: String,
    val kind: KindUi,
    val title: String,
    val description: String,
    val content: String,
    val titleLimit: Int,
    val descriptionLimit: Int,
    val contentLimit: Int,
) {
    /** A draft needs a title and content, and every text within its limit. */
    val isValid: Boolean
        get() = title.isNotBlank() && content.isNotBlank() && title.length <= titleLimit &&
            description.length <= descriptionLimit && content.length <= contentLimit
}

/** Why the registry cannot be shown or changed. */
internal enum class LearningErrorUi {
    /** The stored registry could not be read; nothing can change until it loads. */
    LoadFailed,

    /** The last change could not be written; it applies until the profile closes. */
    SaveFailed,

    /** The registry refused an edit: an empty or too long text, or a title already taken. */
    EditRejected,
}

/** The registry screen: approval level, project filter and instructions with their pending edit or removal. */
@Immutable
internal data class AgentLearningScreenState(
    val isLoaded: Boolean = false,
    val approval: ApprovalUi = ApprovalUi.Automatic,
    val instructions: ImmutableList<InstructionUi> = persistentListOf(),
    val projects: ImmutableList<ProjectUi> = persistentListOf(),
    val filter: ProjectFilterUi = ProjectFilterUi.All,
    val expanded: String? = null,
    val draft: DraftUi? = null,
    val deleting: String? = null,
    val error: LearningErrorUi? = null,
) : MVIState {
    /** Instructions passing [filter], in registry order. */
    val visible: ImmutableList<InstructionUi>
        get() = when (val current = filter) {
            ProjectFilterUi.All -> instructions
            ProjectFilterUi.WithoutProject -> instructions.filter { it.projectKey == null }.toImmutableList()
            is ProjectFilterUi.Project -> instructions.filter { it.projectKey == current.key }.toImmutableList()
        }
}

/** Controls of the registry screen. */
internal sealed interface AgentLearningScreenIntent : MVIIntent {
    /** Changes how new instructions are accepted. */
    data class SelectApproval(val approval: ApprovalUi) : AgentLearningScreenIntent

    /** Turns an instruction on or off. */
    data class SetEnabled(val id: String, val isEnabled: Boolean) : AgentLearningScreenIntent

    /** Shows instructions of one scope. */
    data class SelectFilter(val filter: ProjectFilterUi) : AgentLearningScreenIntent

    /** Shows or hides the text of an instruction. */
    data class ToggleExpanded(val id: String) : AgentLearningScreenIntent

    /** Controls of the instruction editor. */
    sealed interface DraftIntent : AgentLearningScreenIntent

    /** Opens the editor for an instruction. */
    data class Edit(val id: String) : DraftIntent

    /** Replaces the texts being edited. */
    data class ChangeDraft(val title: String, val description: String, val content: String) : DraftIntent

    /** Saves the edited texts. */
    data object SaveDraft : DraftIntent

    /** Closes the editor without saving. */
    data object CancelDraft : DraftIntent

    /** Asks to confirm the removal of an instruction. */
    data class Delete(val id: String) : AgentLearningScreenIntent

    /** Removes the instruction awaiting confirmation. */
    data object ConfirmDelete : AgentLearningScreenIntent

    /** Keeps the instruction awaiting confirmation. */
    data object CancelDelete : AgentLearningScreenIntent

    /** Reads the registry again after a failed load. */
    data object Reload : AgentLearningScreenIntent

    /** Hides a save or edit problem the user has seen. */
    data object DismissError : AgentLearningScreenIntent
}

/** Reserved contract for one-off screen actions. */
internal sealed interface AgentLearningScreenAction : MVIAction
