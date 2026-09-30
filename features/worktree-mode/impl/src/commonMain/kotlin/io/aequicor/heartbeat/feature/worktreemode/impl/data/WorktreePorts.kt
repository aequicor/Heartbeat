package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildCommand
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildOperation
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPlan
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Filesystem-bearing journal data never crosses into machine/UI state or diagnostics. */
@Serializable
internal data class WorktreeRecord(
    val task: WorktreeTask,
    val sourceDirectory: String,
    val directory: String,
    val commonDirectory: String,
    val provisioning: String,
    val jobs: Map<String, BuildExecution> = emptyMap(),
    val mergeLease: BuildExecution? = null,
    val mainSession: io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef? = null,
    val mergeTargetCommit: String? = null,
    val pullRequestBase: String? = null,
) {
    override fun toString(): String = "WorktreeRecord"
}

/** Plan captured before running git worktree add. */
internal data class WorktreeProvision(
    val sourceDirectory: String,
    val directory: String,
    val commonDirectory: String,
    val sourceBranch: String?,
    val branch: String,
    val baseCommit: String,
    val pullRequestBase: String? = sourceBranch,
) {
    override fun toString(): String = "WorktreeProvision"
}

/** Explicit argv and canonical lock resources delivered to the worker in a private job file. */
@Serializable
internal data class BuildExecution(
    val id: String,
    val command: WorktreeBuildCommand,
    val directory: String,
    val resources: List<String>,
    val jobDirectory: String,
    @SerialName("hold") val isHold: Boolean = false,
    val parentProcess: Long = 0L,
    val parentStartedAt: String? = null,
    val configurationRevision: Long = 0,
    val enqueueOrder: Long = 0,
) {
    override fun toString(): String = "BuildExecution"
}

/** Desktop-only Git/filesystem primitives; errors carry safe codes rather than process output. */
internal interface WorktreeGit {
    val isAvailable: Boolean
    suspend fun plan(source: String, identity: String): WorktreeProvision
    suspend fun trackMain(source: String): WorktreeProvision
    suspend fun materialize(record: WorktreeRecord)
    suspend fun exists(record: WorktreeRecord): Boolean
    suspend fun validatePlan(record: WorktreeRecord, plan: WorktreeBuildPlan)
    suspend fun build(record: WorktreeRecord, id: String, command: String): BuildExecution
    suspend fun actionPrompt(record: WorktreeRecord, merge: Boolean): String

    /** Live-agent queue reservation; native commands need explicit session recovery after lost observation. */
    suspend fun mergeLease(record: WorktreeRecord, id: String): BuildExecution
    suspend fun mergePreflight(record: WorktreeRecord): String
    suspend fun verifyAction(record: WorktreeRecord, merge: Boolean, pullRequestUrl: String?): Boolean
}

/** App-wide FIFO scheduler. A worker holds OS locks and survives losing a profile/UI waiter. */
internal interface WorktreeBuildCoordinator {
    suspend fun execute(
        execution: BuildExecution,
        isReattachment: Boolean = false,
        update: suspend (WorktreeBuildOperation) -> Unit,
    ): WorktreeBuildOperation
    suspend fun cancel(id: String): Boolean
    suspend fun recover(execution: BuildExecution): WorktreeBuildOperation

    /** Serializes cooperative agents; this hold lease does not own external or native shell process trees. */
    suspend fun acquireLease(execution: BuildExecution)
    suspend fun releaseLease(execution: BuildExecution): Boolean
}

/** Command workers inherit the same restricted environment as the facade's local command tools. */
internal fun isSecretEnvironmentName(name: String): Boolean {
    val upper = name.uppercase()
    return listOf("KEY", "TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL", "AUTH").any { it in upper }
}
