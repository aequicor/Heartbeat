package io.aequicor.heartbeat.feature.projectconfig.impl.presentation

import io.aequicor.heartbeat.feature.projectconfig.api.ConfigContentKind
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntity
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblem
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblemCode
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProposal
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigRejection
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSource
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigTaskFailure
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigTaskResult
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigState
import io.aequicor.heartbeat.feature.projectconfig.api.ProposalKind
import io.aequicor.heartbeat.feature.projectconfig.api.ProposalReview
import io.aequicor.heartbeat.feature.projectconfig.api.ReviewVerdict
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** The screen for machine [state] and its own selections; a vanished proposal loses its confirmation. */
internal fun ProjectConfigScreenState.reflecting(state: ProjectConfigState, isSupported: Boolean) = when (state) {
    ProjectConfigState.Off -> copy(phase = if (isSupported) PhaseUi.Off else PhaseUi.Unsupported)

    is ProjectConfigState.Loading -> copy(phase = PhaseUi.Loading)

    is ProjectConfigState.Failed -> copy(phase = PhaseUi.Failed)

    is ProjectConfigState.Ready -> {
        val proposals = state.proposals.map { it.toUi(state, projectNames) }.toImmutableList()
        copy(
            phase = PhaseUi.Ready,
            proposals = proposals,
            projects = state.projects.map { (ref, sync) ->
                ProjectUi(
                    key = ref.value,
                    name = projectNames[ref.value] ?: ref.value,
                    hasFolder = sync.hasFolder,
                    isSyncing = sync.isSyncing,
                    isUnreadable = sync.failure != null,
                    units = state.bindings.asSequence().filter { it.project == ref }
                        .map { binding ->
                            BoundUnitUi(
                                binding.unit.section.toUi(),
                                binding.unit.section.value,
                                binding.unit.name,
                                isWriting = state.writing.any { it.project == ref && it.unit == binding.unit },
                            )
                        }
                        .sortedBy { "${it.sectionId}/${it.name}" }
                        .toImmutableList(),
                )
            }.sortedBy { it.name.lowercase() }.toImmutableList(),
            bundle = state.bundle?.name,
            isBusy = state.task != null,
            confirming = confirming?.takeIf { seen -> proposals.any { it.version == seen && it.isTrustOffered } },
            removing = removing?.takeIf { removal ->
                state.bindings.any { it.project.value == removal.projectKey && it.unit.toString() == removal.unit.ref }
            },
        )
    }
}

/** Entities of every available section, in section order and then by title. */
internal fun entitiesOf(entities: Map<ConfigSectionId, List<ConfigEntity>>): ImmutableList<EntityUi> =
    entities.entries.sortedBy { it.key.toUi().ordinal }.flatMap { (section, list) ->
        list.sortedBy { it.title.lowercase() }.map { entity ->
            val parent = entity.parent?.let { "${ConfigSectionId.Harnesses}|$it" }
            EntityUi("$section|${entity.key}", section.toUi(), entity.title, entity.name, parent)
        }
    }.toImmutableList()

/** Notice of a finished task; null when the task asks the user instead. */
internal fun ConfigTaskResult.toNotice(): NoticeUi? = when (this) {
    is ConfigTaskResult.Saved -> NoticeUi.Saved(units, skipped)
    is ConfigTaskResult.Exported -> NoticeUi.Exported(units)
    is ConfigTaskResult.Opened -> NoticeUi.Opened(proposals)
    is ConfigTaskResult.Imported -> NoticeUi.Imported(units, skipped)
    is ConfigTaskResult.ImportConflicts -> null
    is ConfigTaskResult.Failed -> NoticeUi.Failed(reason.toUi())
}

private fun ConfigTaskFailure.toUi(): TaskFailureUi = when (this) {
    ConfigTaskFailure.Busy -> TaskFailureUi.Busy
    ConfigTaskFailure.Unavailable -> TaskFailureUi.Unavailable
    ConfigTaskFailure.Unreadable -> TaskFailureUi.Unreadable
    ConfigTaskFailure.Format -> TaskFailureUi.Format
    ConfigTaskFailure.Version -> TaskFailureUi.Version
    ConfigTaskFailure.Limit -> TaskFailureUi.Limit
    ConfigTaskFailure.Unwritable -> TaskFailureUi.Unwritable
    ConfigTaskFailure.Empty -> TaskFailureUi.Empty
    ConfigTaskFailure.Name -> TaskFailureUi.Name
}

private fun ConfigProposal.toUi(state: ProjectConfigState.Ready, names: Map<String, String>): ProposalUi {
    val source = when (val origin = source) {
        is ConfigSource.Project -> SourceUi(names[origin.project.value] ?: origin.project.value, isBundle = false)
        is ConfigSource.Bundle -> SourceUi(state.bundle?.name.orEmpty(), isBundle = true)
    }
    return ProposalUi(
        id = id,
        digest = digest?.value,
        title = summary?.title?.takeIf { it.isNotBlank() } ?: unit.name,
        unit = unit.name,
        section = unit.section.toUi(),
        source = source,
        kind = kind.toUi(),
        contents = summary?.contents.orEmpty().map { ContentUi(it.kind.toUi(), it.name) }.toImmutableList(),
        hasCode = summary?.hasCode == true,
        review = review.toUi(),
        problems = problems.map { it.toUi() }.toImmutableList(),
        failure = failure?.toUi(),
        isApplying = isApplying,
        isKeepAppOffered = this.source is ConfigSource.Project && target != null && kind != ProposalKind.Create,
        isWriting = state.writing.any { ConfigSource.Project(it.project) == this.source && it.unit == unit },
    )
}

private fun ProposalReview.toUi(): ReviewUi = when (this) {
    ProposalReview.NotNeeded -> ReviewUi.NotNeeded

    ProposalReview.Pending -> ReviewUi.Pending

    ProposalReview.Unavailable -> ReviewUi.Unavailable

    is ProposalReview.Done -> when (verdict) {
        ReviewVerdict.Safe -> ReviewUi.Safe(reason)
        ReviewVerdict.Risk -> ReviewUi.Risky(reason, isUncertain = false)
        ReviewVerdict.Uncertain -> ReviewUi.Risky(reason, isUncertain = true)
    }
}

private fun ProposalKind.toUi(): ProposalKindUi = when (this) {
    ProposalKind.Create -> ProposalKindUi.Create
    ProposalKind.Update -> ProposalKindUi.Update
    ProposalKind.Remove -> ProposalKindUi.Remove
    ProposalKind.Conflict -> ProposalKindUi.Conflict
}

private fun ConfigProblem.toUi() = ProblemUi(
    path = path?.value,
    code = when (code) {
        ConfigProblemCode.Syntax -> ProblemCodeUi.Syntax
        ConfigProblemCode.Missing -> ProblemCodeUi.Missing
        ConfigProblemCode.Unsupported -> ProblemCodeUi.Unsupported
        ConfigProblemCode.Name -> ProblemCodeUi.Name
        ConfigProblemCode.Limit -> ProblemCodeUi.Limit
        ConfigProblemCode.Compile -> ProblemCodeUi.Compile
        ConfigProblemCode.Parent -> ProblemCodeUi.Parent
    },
    detail = detail,
)

private fun ConfigRejection.toUi(): FailureUi = when (this) {
    ConfigRejection.Unavailable -> FailureUi.Unavailable
    ConfigRejection.Conflict -> FailureUi.Conflict
    ConfigRejection.Duplicate -> FailureUi.Duplicate
    ConfigRejection.Limit -> FailureUi.Limit
    ConfigRejection.Invalid -> FailureUi.Invalid
    ConfigRejection.MissingParent -> FailureUi.MissingParent
    ConfigRejection.NotFound, ConfigRejection.Failed -> FailureUi.Failed
}

private fun ConfigContentKind.toUi(): ContentKindUi = when (this) {
    ConfigContentKind.Skill -> ContentKindUi.Skill
    ConfigContentKind.Instruction -> ContentKindUi.Instruction
    ConfigContentKind.Template -> ContentKindUi.Template
    ConfigContentKind.Script -> ContentKindUi.Script
    ConfigContentKind.Workflow -> ContentKindUi.Workflow
    ConfigContentKind.ToolPolicy -> ContentKindUi.ToolPolicy
    ConfigContentKind.Interface -> ContentKindUi.Interface
    ConfigContentKind.LearnedInstruction, ConfigContentKind.LearnedSkill -> ContentKindUi.Lesson
}

private fun ConfigSectionId.toUi(): SectionUi = when (this) {
    ConfigSectionId.Harnesses -> SectionUi.Harness
    ConfigSectionId.Interfaces -> SectionUi.Interface
    ConfigSectionId.Learning -> SectionUi.Learning
    else -> SectionUi.Other
}
