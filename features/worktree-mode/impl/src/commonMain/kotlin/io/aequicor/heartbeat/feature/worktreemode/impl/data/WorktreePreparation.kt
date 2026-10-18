package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.worktreemode.api.VirtualSandboxState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeModeEnabled
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeSandboxEnabled
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlin.uuid.Uuid

/** A serialized preparation writes its complete plan before materializing anything outside the journal. */
internal class WorktreePreparation(
    private val journal: WorktreeJournal,
    private val git: WorktreeGit,
    private val workspaces: LocalWorkspaces,
    private val toggles: FeatureToggles,
) {
    suspend fun execute(command: WorktreeIntent.Public.Prepare, emit: suspend (WorktreeTask) -> Unit) {
        validate(command)
        val known = journal.record(command.chatId)
        if (known != null && known.provisioning.isNotBlank()) {
            validateExisting(command, known.task)
            check(git.isAvailable && git.exists(known)) { "WorktreeUnavailable" }
            emit(known.task)
            return
        }
        check(git.isAvailable && toggles.get(WorktreeModeEnabled)) { "WorktreeModeUnavailable" }
        val source = checkNotNull(workspaces.resolve(command.project)) { "ProjectUnavailable" }
        val isSandbox = command.isSandbox != false && toggles.get(WorktreeSandboxEnabled)
        check(!command.isBasePinned || isSandbox) { "SandboxUnavailable" }
        val identity = Uuid.random().toString()
        val initial = git.plan(source, identity)
        val plan = command.baseCommit?.let { git.pin(initial, it) } ?: initial
        val task = WorktreeTask(
            command.chatId,
            command.project,
            sourceBranch = plan.sourceBranch,
            branch = plan.branch,
            baseCommit = plan.baseCommit,
            pinnedBaseCommit = plan.baseCommit.takeIf { command.isBasePinned },
            isSandbox = isSandbox,
            // Live virtual sources may rebase; a pinned stage uses the existing physical snapshot-build sandbox.
            virtualSandbox = if (!command.isBasePinned && isSandbox && git.isVirtualSupported) {
                VirtualSandboxState(baseCommit = plan.baseCommit, snapshot = plan.baseCommit)
            } else {
                null
            },
        )
        val pending = WorktreeRecord(
            task,
            plan.sourceDirectory,
            plan.directory,
            plan.commonDirectory,
            identity,
            pullRequestBase = plan.pullRequestBase,
            parentWorkspace = command.parentWorkspace,
        )
        journal.provision(pending)
        emit(task)
        git.materialize(pending)
        val workspace = git.virtualWorkspace(pending) ?: workspaces.registerManaged(plan.directory).ref
        journal.provision(pending, workspace)?.let { emit(it) }
    }

    private fun validate(command: WorktreeIntent.Public.Prepare) {
        require(command.chatId.isNotBlank()) { "InvalidChatIdentity" }
        require(command.baseCommit == null || command.isBasePinned) { "PinnedBaseRequired" }
        require(!command.isBasePinned || (command.isSandbox == true && command.parentWorkspace == null)) {
            "PinnedSandboxRequired"
        }
    }

    private fun validateExisting(command: WorktreeIntent.Public.Prepare, task: WorktreeTask) {
        check(task.project == command.project) { "ProjectMismatch" }
        if (command.isBasePinned) {
            check(
                task.isSandbox && task.pinnedBaseCommit != null &&
                    (command.baseCommit == null || task.pinnedBaseCommit == command.baseCommit),
            ) { "PinnedBaseMismatch" }
        }
    }
}
