package io.aequicor.heartbeat.feature.projectconfig.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.TransitionBuilder
import io.aequicor.heartbeat.core.statemachine.TransitionScope
import io.aequicor.heartbeat.core.statemachine.machineSpec
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent.Internal
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent.Public

/**
 * Live project configuration of the profile. The profile libraries stay where entities run: a unit from a project
 * folder or a bundle reaches them only after its automatic review and the user's trust, so they always hold a version
 * the user trusted. Project-owned entities are automatically written into their project's tree by default;
 * changes made in the app are written back into bound folders. App storage suspends all access to that project's
 * tree while keeping the runtime libraries and bindings. Location changes leave Ready to cancel its effects before
 * persisting the new policy and reloading. The machine never reads files or
 * calls the owners of entities: effects read folders, review, apply through the sections and write. Other features
 * send only Public intents (Reload, Sync, Dismiss, OpenBundle, CloseBundle); trusting proposals and writing folders or
 * bundles (Trust, KeepApp, RemoveFromProject, SaveToProject, Export, ImportToProject) are Internal commands of the
 * feature's own screen.
 *
 * | From | Intent | Guard | To | Effect / output |
 * |---|---|---|---|---|
 * | Off | Enabled | | Loading(projects) | Load |
 * | Loading | Loaded | | Ready(bindings, storage) | Sync(project storage only) |
 * | Ready | SetDefaultStorage / SetProjectStorage | known project | Configuring | SaveStorage |
 * | Configuring | StorageSaved / StorageSaveFailed | | Loading | Load; StorageFailed on failure |
 * | Configuring | ProjectsChanged | | same with projects | |
 * | Loading | LoadFailed | | Failed | StorageFailed |
 * | Loading / Failed | ProjectsChanged | | same with projects | |
 * | Failed | Reload | | Loading | Load |
 * | Ready | ProjectsChanged | | added synced, proposals of removed dropped | Sync(added) |
 * | Ready | Sync / Changed | known project, idle | generation + 1, syncing | Sync |
 * | Ready | Sync / Changed | known project, syncing | dirty | |
 * | Ready | Synced | current generation | reconciled | SaveBindings, WriteBack, Review, Sync if dirty |
 * | Ready | SyncFailed | current generation | idle with failure | Sync if dirty |
 * | Ready | Reviewed / ReviewsFailed | | review of the proposal at that digest | |
 * | Ready | Trust | seen digest; trustable (valid; safe, accepted risk or a removal) | applying | Apply |
 * | Ready | Applied | applying | proposal gone, binding committed | SaveBindings, Sync(project) |
 * | Ready | ApplyFailed | | proposal with failure | |
 * | Ready | KeepApp | seen digest; project Update/Conflict/Remove with a target | proposal stays, writing | WriteBack |
 * | Ready | Dismiss | seen digest; not applying | proposal gone until its digest changes | |
 * | Ready | RemoveFromProject | bound unit | writing the deletion | WriteBack |
 * | Ready | WrittenBack | | binding committed or dropped, proposals of the unit gone | SaveBindings, Sync |
 * | Ready | WriteBackFailed | | not writing; proposal or binding stays | WriteBackFailed |
 * | Ready | WriteBackConflict | | not writing | Sync(project) |
 * | Ready | SaveToProject / Export / OpenBundle / ImportToProject | no task | task | the task's effect |
 * | Ready | the same | another task | Ready | TaskFinished(Busy) |
 * | Ready | TaskDone | the task | no task, new bindings | SaveBindings, Sync(project), TaskFinished |
 * | Ready | BundleOpened | the task | bundle and its proposals | CloseBundle(previous), Review, TaskFinished |
 * | Ready | CloseBundle | open bundle | bundle proposals gone | CloseBundle |
 * | Ready | BindingsSaveFailed | | Ready | StorageFailed |
 * | Loading / Failed / Ready | Disabled | | Off (effects cancelled) | |
 *
 * Effect failures: Load → LoadFailed, SaveBindings → BindingsSaveFailed, Sync → SyncFailed(Io), Review →
 * ReviewsFailed, Apply → ApplyFailed(Failed), WriteBack → WriteBackFailed, tasks → TaskDone(Failed(Unavailable)).
 */
public val ProjectConfigMachineSpec: MachineSpec<
    ProjectConfigState,
    ProjectConfigIntent,
    ProjectConfigEffect,
    ProjectConfigOutput,
> = machineSpec(ProjectConfigMachineKey, ProjectConfigState.Off) {
    state<ProjectConfigState.Off> {
        on<Internal.Enabled> {
            goto<ProjectConfigState.Loading> { ProjectConfigState.Loading(intent.projects) }
            effect { ProjectConfigEffect.Load }
        }
        on<Internal.Disabled>()
    }
    state<ProjectConfigState.Loading> {
        on<Internal.Loaded> {
            goto<ProjectConfigState.Ready> { loaded(intent.bindings, state.projects, intent.storage).state }
            effect { loaded(intent.bindings, state.projects, intent.storage).effects.firstOrNull() }
        }
        on<Internal.LoadFailed> {
            goto<ProjectConfigState.Failed> { ProjectConfigState.Failed(state.projects) }
            output { ProjectConfigOutput.StorageFailed }
        }
        on<Internal.ProjectsChanged> { stay { ProjectConfigState.Loading(intent.projects) } }
    }
    state<ProjectConfigState.Configuring> {
        on<Internal.StorageSaved> {
            goto<ProjectConfigState.Loading> { ProjectConfigState.Loading(state.projects) }
            effect { ProjectConfigEffect.Load }
        }
        on<Internal.StorageSaveFailed> {
            goto<ProjectConfigState.Loading> { ProjectConfigState.Loading(state.projects) }
            effect { ProjectConfigEffect.Load }
            output { ProjectConfigOutput.StorageFailed }
        }
        on<Internal.ProjectsChanged> { stay { state.copy(projects = intent.projects) } }
    }
    state<ProjectConfigState.Failed> {
        on<Public.Reload> {
            goto<ProjectConfigState.Loading> { ProjectConfigState.Loading(state.projects) }
            effect { ProjectConfigEffect.Load }
        }
        on<Internal.ProjectsChanged> { stay { ProjectConfigState.Failed(intent.projects) } }
    }
    state<ProjectConfigState.Ready> {
        on<Internal.SetDefaultStorage> {
            goto<ProjectConfigState.Configuring> { ProjectConfigState.Configuring(state.projects.keys) }
            effect { ProjectConfigEffect.SaveStorage(state.storage.copy(default = intent.location), state.bindings) }
        }
        on<Internal.SetProjectStorage>(guard = { intent.project in state.projects }) {
            goto<ProjectConfigState.Configuring> { ProjectConfigState.Configuring(state.projects.keys) }
            effect {
                val projects = intent.location?.let { state.storage.projects + (intent.project.value to it) }
                    ?: (state.storage.projects - intent.project.value)
                ProjectConfigEffect.SaveStorage(state.storage.copy(projects = projects), state.bindings)
            }
        }
        syncs()
        decisions()
        tasks()
        on<Internal.BindingsSaveFailed> { output { ProjectConfigOutput.StorageFailed } }
    }
    any { on<Internal.Disabled> { goto<ProjectConfigState.Off> { ProjectConfigState.Off } } }
    onEffectFailure { effect, _ -> effect.failure() }
}

private typealias ReadyBuilder = io.aequicor.heartbeat.core.statemachine.StateBuilder<
    ProjectConfigState,
    ProjectConfigState.Ready,
    ProjectConfigIntent,
    ProjectConfigEffect,
    ProjectConfigOutput,
>

private fun ReadyBuilder.syncs() {
    on<Internal.ProjectsChanged> { step { state.withProjects(intent.projects) } }
    on<Public.Sync>(guard = { intent.project in state.projects }) { step { state.changed(intent.project) } }
    on<Internal.Changed>(guard = { intent.project in state.projects }) { step { state.changed(intent.project) } }
    on<Internal.Synced>(guard = { state.isCurrent(intent.snapshot.project, intent.snapshot.generation) }) {
        step { state.synced(intent) }
    }
    on<Internal.SyncFailed> { step { state.syncFailed(intent) } }
    on<Internal.WrittenBack> { step { state.writtenBack(intent) } }
    on<Internal.WriteBackFailed> {
        stay { state.copy(writing = state.writing - intent.writeBacks.toSet()) }
        output { ProjectConfigOutput.WriteBackFailed }
    }
    on<Internal.WriteBackConflict> {
        step { state.copy(writing = state.writing - intent.writeBack).changed(intent.writeBack.project) }
    }
}

private fun ReadyBuilder.decisions() {
    on<Internal.Reviewed> {
        stay {
            state.reviewed(
                listOf(reviewKey(intent.proposal, intent.digest) to intent.review),
            )
        }
    }
    on<Internal.ReviewsFailed> {
        stay { state.reviewed(intent.proposals.map { it.reviewKey to ProposalReview.Unavailable }) }
    }
    on<Internal.Trust>(
        guard = { state.idleProposal(intent.proposal, intent.digest)?.isTrustable(intent.isRiskAccepted) == true },
    ) {
        step { state.trusted(checkNotNull(state.proposal(intent.proposal))) }
    }
    on<Internal.Applied> { step { state.applied(intent) } }
    on<Internal.ApplyFailed> {
        stay {
            state.copy(
                proposals = state.proposals.map {
                    if (it.id == intent.proposal && it.isApplying) {
                        it.copy(
                            isApplying = false,
                            failure = intent.reason,
                        )
                    } else {
                        it
                    }
                },
            )
        }
    }
    on<Internal.KeepApp>(guard = { state.keptWriteBack(intent.proposal, intent.digest) != null }) {
        step { state.writing(checkNotNull(state.keptWriteBack(intent.proposal, intent.digest))) }
    }
    on<Public.Dismiss>(
        guard = { state.idleProposal(intent.proposal, intent.digest) != null },
    ) {
        stay {
            val proposal = checkNotNull(state.proposal(intent.proposal))
            state.copy(proposals = state.proposals - proposal, dismissed = state.dismissed + proposal.reviewKey)
        }
    }
    on<Internal.RemoveFromProject>(
        guard = {
            state.storage.location(intent.project) == ConfigStorage.Project && state.bindings.any {
                !it.isExcluded && it.project == intent.project && it.unit == intent.unit
            }
        },
    ) {
        step {
            val binding = state.bindings.first { it.project == intent.project && it.unit == intent.unit }
            val proposal = state.proposals.firstOrNull { it.projectOrNull == intent.project && it.unit == intent.unit }
            val digest = if (proposal != null) proposal.digest else binding.fileDigest
            state.writing(ConfigWriteBack(intent.project, intent.unit, null, digest, isUnbinding = true))
        }
    }
}

private fun ReadyBuilder.tasks() {
    on<Internal.SaveToProject>(
        guard = {
            intent.project in state.projects && state.storage.location(intent.project) == ConfigStorage.Project &&
                intent.entities.isNotEmpty()
        },
    ) {
        step {
            state.started(
                ConfigTask(intent.requestId, ConfigTaskKind.SaveToProject, intent.project),
                ProjectConfigEffect.SaveToProject(intent.requestId, intent.project, intent.entities),
            )
        }
    }
    on<Internal.Export>(guard = { intent.entities.isNotEmpty() }) {
        step {
            state.started(
                ConfigTask(intent.requestId, ConfigTaskKind.Export),
                ProjectConfigEffect.Export(intent.requestId, intent.path, intent.entities),
            )
        }
    }
    on<Public.OpenBundle> {
        step {
            state.started(
                ConfigTask(intent.requestId, ConfigTaskKind.OpenBundle),
                ProjectConfigEffect.OpenBundle(intent.requestId, intent.path),
            )
        }
    }
    on<Internal.ImportToProject>(
        guard = { intent.project in state.projects && state.storage.location(intent.project) == ConfigStorage.Project },
    ) {
        step {
            state.started(
                ConfigTask(intent.requestId, ConfigTaskKind.ImportToProject, intent.project),
                ProjectConfigEffect.ImportToProject(intent.requestId, intent.path, intent.project, intent.existing),
            )
        }
    }
    on<Internal.TaskDone>(guard = { state.task?.requestId == intent.requestId }) { step { state.taskDone(intent) } }
    on<Internal.BundleOpened>(guard = { state.task?.requestId == intent.requestId }) {
        step { state.bundleOpened(intent) }
    }
    on<Public.CloseBundle>(guard = { state.bundle != null }) {
        stay {
            state.copy(bundle = null, proposals = state.proposals.filterNot { it.source is ConfigSource.Bundle })
        }
        effect { state.bundle?.let { ProjectConfigEffect.CloseBundle(it.id) } }
    }
}

/** Applies [compute] as a Ready → Ready transition with up to [EFFECTS] effects and [OUTPUTS] outputs. */
private fun <J : ProjectConfigIntent> TransitionBuilder<
    ProjectConfigState,
    ProjectConfigState.Ready,
    ProjectConfigIntent,
    J,
    ProjectConfigEffect,
    ProjectConfigOutput,
>.step(
    compute: TransitionScope<ProjectConfigState.Ready, J>.() -> ConfigStep,
) {
    stay {
        val next = compute()
        // A transition has fixed effect and output slots: more would be dropped silently.
        check(next.effects.size <= EFFECTS && next.outputs.size <= OUTPUTS) { "Too many effects of a step" }
        next.state
    }
    repeat(EFFECTS) { index -> effect { compute().effects.getOrNull(index) } }
    repeat(OUTPUTS) { index -> output { compute().outputs.getOrNull(index) } }
}

/** The shown proposal only while neither applying nor writing its unit. */
private fun ProjectConfigState.Ready.idleProposal(id: String, digest: ConfigDigest?): ConfigProposal? =
    proposal(id, digest)?.takeUnless { proposal -> proposal.isApplying || writing.any { proposal.isFor(it) } }

/** The write-back that keeps the profile's version against the files of a project proposal, or null. */
private fun ProjectConfigState.Ready.keptWriteBack(id: String, digest: ConfigDigest?): ConfigWriteBack? {
    val proposal = idleProposal(id, digest) ?: return null
    val project = (proposal.source as? ConfigSource.Project)?.project ?: return null
    val target = proposal.target?.takeIf { proposal.kind != ProposalKind.Create } ?: return null
    return ConfigWriteBack(project, proposal.unit, target, proposal.digest)
}

private fun ProjectConfigEffect.failure(): ProjectConfigIntent? = when (this) {
    ProjectConfigEffect.Load -> Internal.LoadFailed
    is ProjectConfigEffect.SaveBindings -> Internal.BindingsSaveFailed
    is ProjectConfigEffect.SaveStorage -> Internal.StorageSaveFailed
    is ProjectConfigEffect.Sync -> Internal.SyncFailed(requests, ConfigReadFailure.Io)
    is ProjectConfigEffect.Review -> Internal.ReviewsFailed(proposals)
    is ProjectConfigEffect.Apply -> Internal.ApplyFailed(proposal.id, ConfigRejection.Failed)
    is ProjectConfigEffect.WriteBack -> Internal.WriteBackFailed(writeBacks)
    is ProjectConfigEffect.SaveToProject -> taskFailed(requestId)
    is ProjectConfigEffect.Export -> taskFailed(requestId)
    is ProjectConfigEffect.OpenBundle -> taskFailed(requestId)
    is ProjectConfigEffect.ImportToProject -> taskFailed(requestId)
    is ProjectConfigEffect.CloseBundle -> null
}

private fun taskFailed(requestId: String) =
    Internal.TaskDone(requestId, ConfigTaskResult.Failed(ConfigTaskFailure.Unavailable))

private const val EFFECTS = 4
private const val OUTPUTS = 1
