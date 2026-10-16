package io.aequicor.heartbeat.feature.projectconfig.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/** The first Ready state after the bindings were read, syncing every project. */
internal fun loaded(bindings: List<ConfigBinding>, projects: Set<WorkspaceRef>): ConfigStep =
    Ready(bindings = bindings).withProjects(projects)

/** Starts syncing added projects and forgets the proposals of removed ones; their bindings stay. */
internal fun Ready.withProjects(projects: Set<WorkspaceRef>): ConfigStep {
    val added = projects - this.projects.keys
    val removed = this.projects.keys - projects
    val next = copy(
        projects = this.projects.filterKeys { it in projects } + added.associateWith {
            ProjectSync(generation = 1, isSyncing = true)
        },
        proposals = proposals.filterNot { it.projectOrNull in removed },
    )
    val requests = added.map { SyncRequest(it, 1, bindingsOf(it)) }
    return ConfigStep(next, listOfNotNull(ProjectConfigEffect.Sync(requests).takeIf { requests.isNotEmpty() }))
}

/** Syncs [project] again, or marks it dirty while a sync is running. */
internal fun Ready.changed(project: WorkspaceRef): ConfigStep {
    val sync = projects[project] ?: return ConfigStep(this)
    if (sync.isSyncing) return ConfigStep(copy(projects = projects + (project to sync.copy(isDirty = true))))
    val generation = sync.generation + 1
    val next = copy(projects = projects + (project to sync.copy(generation = generation, isSyncing = true)))
    return ConfigStep(
        next,
        listOf(ProjectConfigEffect.Sync(listOf(SyncRequest(project, generation, bindingsOf(project))))),
    )
}

/** Whether [snapshot] answers the running sync of its project. */
internal fun Ready.isCurrent(project: WorkspaceRef, generation: Long): Boolean =
    projects[project]?.let { it.isSyncing && it.generation == generation } == true

/** Reconciles a sync result: bindings, proposals, write-backs and reviews; a dirty project is synced again. */
internal fun Ready.synced(intent: ProjectConfigIntent.Internal.Synced): ConfigStep {
    val snapshot = intent.snapshot
    val project = snapshot.project
    val sync = checkNotNull(projects[project])
    val previous = proposals.filter { it.projectOrNull == project }
    val result = reconcile(bindings, snapshot, previous)
    // A proposal being applied stays as it is: its result commits the binding the sync could not know yet.
    val applying = previous.filter { it.isApplying }
    val applyingIds = applying.map { it.id }.toSet()
    val shown = applying + result.proposals.filterNot { it.reviewKey in dismissed || it.id in applyingIds }
    val writes = result.writeBacks.filterNot { next ->
        writing.any { it.project == next.project && it.unit == next.unit }
    }
    val reviews = shown.filter { it.isReviewDue && it.reviewKey !in reviewing }
    val next = copy(
        proposals = proposals.filterNot { it.projectOrNull == project } + shown,
        writing = writing + writes,
        reviewing = reviewing + reviews.map { it.reviewKey },
        projects = projects + (
            project to sync.copy(
                isSyncing = false,
                isDirty = false,
                hasFolder = intent.hasFolder,
                failure = null,
                ignored = snapshot.ignored,
            )
        ),
    )
    val effects = listOfNotNull(
        ProjectConfigEffect.WriteBack(writes).takeIf { writes.isNotEmpty() },
        ProjectConfigEffect.Review(reviews).takeIf { reviews.isNotEmpty() },
    )
    return ConfigStep(next, effects)
        .then { it.withBindings(bindings.filterNot { binding -> binding.project == project } + result.bindings) }
        .then { if (sync.isDirty) it.changed(project) else ConfigStep(it) }
}

/** Ends the syncs of [intent] that are still current with its failure; dirty projects are synced again. */
internal fun Ready.syncFailed(intent: ProjectConfigIntent.Internal.SyncFailed): ConfigStep =
    intent.requests.filter { isCurrent(it.project, it.generation) }.fold(ConfigStep(this)) { step, request ->
        step.then { state ->
            val sync = checkNotNull(state.projects[request.project])
            val ended = sync.copy(isSyncing = false, isDirty = false, failure = intent.failure)
            val next = state.copy(projects = state.projects + (request.project to ended))
            if (sync.isDirty) next.changed(request.project) else ConfigStep(next)
        }
    }

/** Starts writing [writeBack] unless its unit is being written; its proposal stays until the write succeeds. */
internal fun Ready.writing(writeBack: ConfigWriteBack): ConfigStep {
    if (writing.any { it.project == writeBack.project && it.unit == writeBack.unit }) return ConfigStep(this)
    return ConfigStep(
        copy(writing = writing + writeBack),
        listOf(ProjectConfigEffect.WriteBack(listOf(writeBack))),
    )
}

/** Commits a write-back: the new binding, or none after its files were deleted. */
internal fun Ready.writtenBack(intent: ProjectConfigIntent.Internal.WrittenBack): ConfigStep {
    val writeBack = intent.writeBack
    val others = bindings.filterNot { it.project == writeBack.project && it.unit == writeBack.unit }
    val next = copy(writing = writing - writeBack, proposals = proposals.filterNot { it.isFor(writeBack) })
    return ConfigStep(next)
        .then { it.withBindings(others + listOfNotNull(intent.binding)) }
        .then { it.changed(writeBack.project) }
}
