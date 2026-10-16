package io.aequicor.heartbeat.feature.projectconfig.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/** The next Ready state with its effects and outputs, computed once per transition. */
internal data class ConfigStep(
    val state: ProjectConfigState.Ready,
    val effects: List<ProjectConfigEffect> = emptyList(),
    val outputs: List<ProjectConfigOutput> = emptyList(),
) {
    /** This step followed by [next] computed from its state. */
    fun then(next: (ProjectConfigState.Ready) -> ConfigStep): ConfigStep {
        val step = next(state)
        return ConfigStep(step.state, effects + step.effects, outputs + step.outputs)
    }
}

internal typealias Ready = ProjectConfigState.Ready

/** Key of a review: one per proposal and digest. */
internal val ConfigProposal.reviewKey: String get() = reviewKey(id, digest)

/** Key of the review of proposal [id] at [digest]. */
internal fun reviewKey(id: String, digest: ConfigDigest?): String = "$id@${digest?.value ?: REMOVED}"

private const val REMOVED = "removed"

/** Bindings of [project]. */
internal fun Ready.bindingsOf(project: WorkspaceRef): List<ConfigBinding> = bindings.filter { it.project == project }

/** The proposal with [id], or null. */
internal fun Ready.proposal(id: String): ConfigProposal? = proposals.firstOrNull { it.id == id }

/** The proposal [id] while it is still in the version with [digest]. */
internal fun Ready.proposal(id: String, digest: ConfigDigest?): ConfigProposal? =
    proposal(id)?.takeIf { it.digest == digest }

/** Stores [next] bindings when they differ as a set from the current ones. */
internal fun Ready.withBindings(next: List<ConfigBinding>): ConfigStep {
    if (next.toSet() == bindings.toSet()) return ConfigStep(this)
    val revision = revision + 1
    return ConfigStep(
        copy(bindings = next, revision = revision),
        listOf(ProjectConfigEffect.SaveBindings(next, revision)),
    )
}

internal val ConfigProposal.projectOrNull: WorkspaceRef? get() = (source as? ConfigSource.Project)?.project

internal val ConfigProposal.isReviewDue: Boolean get() = problems.isEmpty() && review == ProposalReview.Pending

internal fun ConfigProposal.isFor(writeBack: ConfigWriteBack): Boolean =
    projectOrNull == writeBack.project && unit == writeBack.unit && !isApplying

internal fun List<ConfigProposal>.replaced(proposal: ConfigProposal): List<ConfigProposal> =
    map { if (it.id == proposal.id) proposal else it }
