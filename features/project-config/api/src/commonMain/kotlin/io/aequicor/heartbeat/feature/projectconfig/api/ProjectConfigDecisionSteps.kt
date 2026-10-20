package io.aequicor.heartbeat.feature.projectconfig.api

/** Starts applying a trusted proposal. */
internal fun Ready.trusted(proposal: ConfigProposal): ConfigStep {
    val applying = proposal.copy(isApplying = true, failure = null)
    return ConfigStep(copy(proposals = proposals.replaced(applying)), listOf(ProjectConfigEffect.Apply(applying)))
}

/** Commits an applied proposal and the binding it created or removed. */
internal fun Ready.applied(intent: ProjectConfigIntent.Internal.Applied): ConfigStep {
    val proposal = proposals.firstOrNull { it.id == intent.proposal && it.isApplying }
    val next = copy(proposals = proposals.filterNot { it == proposal })
    val project = intent.binding?.project ?: proposal?.projectOrNull ?: return ConfigStep(next)
    val unit = intent.binding?.unit ?: proposal?.unit ?: return ConfigStep(next)
    val others = bindings.filterNot { it.project == project && it.unit == unit }
    // The entity may have changed while it was applied: a new sync reconciles it against the committed binding.
    return ConfigStep(next)
        .then { it.withBindings(others + listOfNotNull(intent.binding)) }
        .then { it.changed(project) }
}

/** Ends task [intent] with its bindings and output; a task that wrote into a project syncs it. */
internal fun Ready.taskDone(intent: ProjectConfigIntent.Internal.TaskDone): ConfigStep {
    val project = task?.project
    val created = intent.bindings.map { it.project to it.unit }.toSet()
    val others = bindings.filterNot { (it.project to it.unit) in created }
    return ConfigStep(
        copy(task = null),
        outputs = listOf(ProjectConfigOutput.TaskFinished(intent.requestId, intent.result)),
    )
        .then { if (intent.bindings.isEmpty()) ConfigStep(it) else it.withBindings(others + intent.bindings) }
        .then { if (project != null) it.changed(project) else ConfigStep(it) }
}

/** Replaces the open bundle with [intent]'s and presents its proposals for explicit consent. */
internal fun Ready.bundleOpened(intent: ProjectConfigIntent.Internal.BundleOpened): ConfigStep {
    val next = copy(
        bundle = intent.bundle,
        task = null,
        proposals = proposals.filterNot { it.source is ConfigSource.Bundle } + intent.proposals,
    )
    val effects = listOfNotNull(
        bundle?.let { ProjectConfigEffect.CloseBundle(it.id) },
    )
    val result = ConfigTaskResult.Opened(intent.proposals.size)
    return ConfigStep(next, effects, listOf(ProjectConfigOutput.TaskFinished(intent.requestId, result)))
}

/** Starts task [task] with [effect], or refuses it while another one runs. */
internal fun Ready.started(task: ConfigTask, effect: ProjectConfigEffect): ConfigStep = if (this.task != null) {
    val busy = ConfigTaskResult.Failed(ConfigTaskFailure.Busy)
    ConfigStep(this, outputs = listOf(ProjectConfigOutput.TaskFinished(task.requestId, busy)))
} else {
    ConfigStep(copy(task = task), listOf(effect))
}
