package io.aequicor.heartbeat.feature.projectconfig.impl.presentation

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState

/** Whether the feature can be used and its data is known. */
internal enum class PhaseUi { Unsupported, Off, Loading, Failed, Ready }

/** Section of a unit or entity as the screen names it. */
internal enum class SectionUi { Harness, Interface, Learning, Other }

/** What trusting a proposal does. */
internal enum class ProposalKindUi { Create, Update, Remove, Conflict }

/** Named part of a unit, by kind. */
internal enum class ContentKindUi { Skill, Instruction, Template, Script, Workflow, ToolPolicy, Interface, Lesson }

/** Result of the automatic review shown on a proposal; [reason] is the reviewer's explanation. */
internal sealed interface ReviewUi {
    /** A removal is confirmed without review. */
    data object NotNeeded : ReviewUi

    /** The reviewer is working on it. */
    data object Pending : ReviewUi

    /** The reviewer could not answer (no default model or a failure). */
    data object Unavailable : ReviewUi

    /** Reviewed safe. */
    data class Safe(val reason: String) : ReviewUi

    /** Reviewed risky or uncertain: trusting it needs an explicit confirmation. */
    data class Risky(val reason: String, val isUncertain: Boolean) : ReviewUi
}

/** A named part of a unit. */
internal data class ContentUi(val kind: ContentKindUi, val name: String)

/** A unit waiting for the confirmation of its removal from a project folder. */
internal data class RemovalUi(val projectKey: String, val projectName: String, val unit: BoundUnitUi)

/** Why a file or unit cannot be used. */
internal enum class ProblemCodeUi { Syntax, Missing, Unsupported, Name, Limit, Compile, Parent }

/** A problem of a proposal: the file it is about (if any), its kind and a short technical [detail]. */
internal data class ProblemUi(val path: String?, val code: ProblemCodeUi, val detail: String)

/** Why a proposal was not applied. */
internal enum class FailureUi { Unavailable, Conflict, Duplicate, Limit, Invalid, MissingParent, Failed }

/** Where a proposal comes from: a project by name, or the open bundle. */
internal data class SourceUi(val name: String, val isBundle: Boolean)

/** One change waiting for the user's decision. */
internal data class ProposalUi(
    val id: String,
    val title: String,
    val unit: String,
    val section: SectionUi,
    val source: SourceUi,
    val kind: ProposalKindUi,
    val contents: ImmutableList<ContentUi>,
    val hasCode: Boolean,
    val review: ReviewUi,
    val problems: ImmutableList<ProblemUi>,
    val failure: FailureUi?,
    val isApplying: Boolean,
    val isKeepAppOffered: Boolean,
) {
    /** Whether "Trust" is offered: valid, reviewed or a removal, not being applied. */
    val isTrustOffered: Boolean get() = problems.isEmpty() && !isApplying && review != ReviewUi.Pending
}

/** A unit of a project folder bound to a profile entity. */
internal data class BoundUnitUi(val section: SectionUi, val sectionId: String, val name: String) {
    /** `section/name`, unique within a project. */
    val ref: String get() = "$sectionId/$name"
}

/** A user project and its `.heartbeat` folder. */
internal data class ProjectUi(
    val key: String,
    val name: String,
    val hasFolder: Boolean,
    val isSyncing: Boolean,
    val isUnreadable: Boolean,
    val units: ImmutableList<BoundUnitUi>,
)

/**
 * A profile entity that can be saved into a project or exported; [id] is `section|key`, [parentId] the id of the
 * entity it belongs to (an interface's harness), which takes it along.
 */
internal data class EntityUi(
    val id: String,
    val section: SectionUi,
    val title: String,
    val name: String,
    val parentId: String? = null,
)

/** The entity chooser: for a project folder ([projectKey]) or for an export. */
internal data class PickerUi(val projectKey: String?, val selected: ImmutableSet<String> = persistentSetOf())

/** One-off result of a task, shown until dismissed. */
internal sealed interface NoticeUi {
    /** [units] entities were written into a project, [skipped] collided with files there. */
    data class Saved(val units: Int, val skipped: Int) : NoticeUi

    /** A bundle of [units] was written. */
    data class Exported(val units: Int) : NoticeUi

    /** A bundle was opened with [proposals] for the profile. */
    data class Opened(val proposals: Int) : NoticeUi

    /** A bundle's [units] were copied into a project. */
    data class Imported(val units: Int) : NoticeUi

    /** A task failed for [reason]. */
    data class Failed(val reason: TaskFailureUi) : NoticeUi

    /** Bindings could not be stored. */
    data object StorageFailed : NoticeUi
}

/** Why a task failed, as shown. */
internal enum class TaskFailureUi { Busy, Unavailable, Unreadable, Format, Version, Limit, Unwritable, Empty }

/** The configuration screen: proposals, projects, the open bundle and the dialogs over them. */
@Immutable
internal data class ProjectConfigScreenState(
    val phase: PhaseUi = PhaseUi.Loading,
    val proposals: ImmutableList<ProposalUi> = persistentListOf(),
    val projects: ImmutableList<ProjectUi> = persistentListOf(),
    val entities: ImmutableList<EntityUi> = persistentListOf(),
    val bundle: String? = null,
    val isBusy: Boolean = false,
    val notice: NoticeUi? = null,
    val confirming: String? = null,
    val picker: PickerUi? = null,
    val removing: RemovalUi? = null,
    val projectNames: ImmutableMap<String, String> = persistentMapOf(),
) : MVIState {
    /** The proposal awaiting confirmation of its risk. */
    val confirmed: ProposalUi? get() = proposals.firstOrNull { it.id == confirming }
}

/** Controls of the configuration screen. */
internal sealed interface ProjectConfigScreenIntent : MVIIntent {
    /** Decisions on proposals and bindings, sent to the machine as they are. */
    sealed interface Decision : ProjectConfigScreenIntent

    /** Choosers, file dialogs, tasks and the notice. */
    sealed interface Task : ProjectConfigScreenIntent

    /** Trusts a proposal; a risky one asks for confirmation first. */
    data class Trust(val id: String) : Decision

    /** Trusts the proposal awaiting confirmation despite its risk. */
    data object ConfirmRisk : Decision

    /** Keeps the proposal unconfirmed. */
    data object CancelRisk : Decision

    /** Writes the profile's version over a project proposal's files. */
    data class KeepApp(val id: String) : Decision

    /** Sets a proposal aside until its files change. */
    data class Dismiss(val id: String) : Decision

    /** Reads a project folder again. */
    data class Sync(val projectKey: String) : Decision

    /** Asks to delete a bound unit from a project folder; the profile entity stays. */
    data class RemoveFromProject(val projectKey: String, val unit: BoundUnitUi) : Decision

    /** Deletes the unit awaiting confirmation. */
    data object ConfirmRemove : Decision

    /** Keeps the unit awaiting confirmation. */
    data object CancelRemove : Decision

    /** Opens the chooser of entities to save into a project, or to export when [projectKey] is null. */
    data class OpenPicker(val projectKey: String?) : Task

    /** Selects or clears an entity in the chooser. */
    data class Toggle(val entityId: String) : Task

    /** Saves or exports the chosen entities. */
    data object ConfirmPicker : Task

    /** Closes the chooser. */
    data object CancelPicker : Task

    /** Picks a bundle to import into the profile. */
    data object OpenBundle : Task

    /** Picks a bundle to copy into a project. */
    data class ImportToProject(val projectKey: String) : Task

    /** Closes the open bundle. */
    data object CloseBundle : Task

    /** Reads the bindings again after a failed load. */
    data object Reload : Decision

    /** Hides the notice. */
    data object DismissNotice : Task
}

/** Reserved contract for one-off screen actions. */
internal sealed interface ProjectConfigScreenAction : MVIAction
