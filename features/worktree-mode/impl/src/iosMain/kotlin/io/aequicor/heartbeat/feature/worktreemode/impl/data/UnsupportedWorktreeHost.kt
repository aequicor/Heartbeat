package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildOperation
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPlan

@Inject
@ContributesBinding(ProfileScope::class)
internal class UnsupportedWorktreeGit : WorktreeGit {
    override val isAvailable: Boolean = false
    override suspend fun plan(source: String, identity: String): WorktreeProvision = unavailable()
    override suspend fun trackMain(source: String): WorktreeProvision = unavailable()
    override suspend fun materialize(record: WorktreeRecord): Unit = unavailable()
    override suspend fun exists(record: WorktreeRecord): Boolean = false
    override suspend fun validatePlan(record: WorktreeRecord, plan: WorktreeBuildPlan): Unit = unavailable()
    override suspend fun build(record: WorktreeRecord, id: String, command: String): BuildExecution = unavailable()
    override suspend fun actionPrompt(record: WorktreeRecord, merge: Boolean): String = unavailable()
    override suspend fun mergeLease(record: WorktreeRecord, id: String): BuildExecution = unavailable()
    override suspend fun mergePreflight(record: WorktreeRecord): String = unavailable()
    override suspend fun verifyAction(record: WorktreeRecord, merge: Boolean, pullRequestUrl: String?): Boolean = false
    private fun unavailable(): Nothing = throw UnsupportedOperationException("Worktrees require Desktop")
}

@Inject
@ContributesBinding(AppScope::class)
internal class UnsupportedWorktreeBuildCoordinator : WorktreeBuildCoordinator {
    override suspend fun execute(
        execution: BuildExecution,
        isReattachment: Boolean,
        update: suspend (WorktreeBuildOperation) -> Unit,
    ): WorktreeBuildOperation = throw UnsupportedOperationException("Build workers require Desktop")
    override suspend fun cancel(id: String): Boolean = false
    override suspend fun recover(execution: BuildExecution): WorktreeBuildOperation =
        throw UnsupportedOperationException("Build workers require Desktop")
    override suspend fun acquireLease(execution: BuildExecution): Unit =
        throw UnsupportedOperationException("Build workers require Desktop")
    override suspend fun releaseLease(execution: BuildExecution): Boolean = false
}
