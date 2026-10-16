package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildCommand
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildOperation
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPlan
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
    @SerialName("outputsReclaimed") val isOutputsReclaimed: Boolean = false,
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

/**
 * Explicit argv and canonical lock resources delivered to the worker in a private job file. [project] is the
 * canonical repository identity that groups builds for fair capacity sharing.
 */
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
    val project: String = "",
    val sandbox: SandboxBuild? = null,
) {
    /** Hold leases only serialize agents; a build is weighed by its declared kind. */
    val cost: BuildCost get() = if (isHold) BuildCost.None else command.kind.cost()

    override fun toString(): String = "BuildExecution"
}

/**
 * Sandbox build target: the immutable [snapshot] commit of the task's files is checked out in a warm build slot of
 * [pool] before the command runs in [relativeDirectory] of that slot. [affinity] (the task identity) prefers the slot
 * that built the task last; [repository] is a checkout of the same repository used to create slots. The coordinator
 * assigns [slot] at admission and adds the slot directory to the exclusive resources.
 */
@Serializable
internal data class SandboxBuild(
    val snapshot: String,
    val pool: String,
    val repository: String,
    val relativeDirectory: String = ".",
    val affinity: String = "",
    val slot: Int? = null,
) {
    override fun toString(): String = "SandboxBuild"
}

/** Sandbox checkout primitives; platforms without sandboxes never create sandbox tasks. */
internal interface SandboxGit {
    /**
     * Commits the sandbox checkout's current files (respecting ignore rules, without touching the agent's index)
     * as an immutable snapshot reachable from a private ref until its build finishes.
     */
    suspend fun snapshot(record: WorktreeRecord, id: String): String =
        throw UnsupportedOperationException("Sandboxes require Desktop")

    /** Removes ignored files (outputs of native builds the agent ran anyway) from a sandbox that no longer works. */
    suspend fun reclaim(record: WorktreeRecord) = Unit

    /** Deletes the private ref of a snapshot whose build was never queued. */
    suspend fun discardSnapshot(record: WorktreeRecord, id: String) = Unit

    /**
     * Deletes snapshot refs of the record's repository that no [active] operation owns and that are older than a
     * grace period, so a build queued by another profile keeps its input.
     */
    suspend fun pruneSnapshots(record: WorktreeRecord, active: Set<String>) = Unit

    /**
     * Removes build slots of the record's repository numbered [keep] and above, with their Git registrations.
     * A slot locked by a worker is skipped; a removed slot is locked for the whole removal.
     */
    suspend fun sweepSlots(record: WorktreeRecord, keep: Int) = Unit
}

/** Desktop-only Git/filesystem primitives; errors carry safe codes rather than process output. */
internal interface WorktreeGit : SandboxGit {
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

/** Current machine-wide budget; the application coordinator reads it on every admission. */
internal interface BuildCapacity {
    /** Estimate from this machine's processors and memory, shown next to the user limit. */
    val automatic: BuildBudget

    /** The effective budget: unlimited without the build queue, else the user limit when set, else [automatic]. */
    suspend fun budget(): BuildBudget

    /** Emits whenever the effective budget may have changed, so waiting builds are admitted again. */
    fun changes(): Flow<Unit> = emptyFlow()
}

/** Command workers inherit the same restricted environment as the facade's local command tools. */
internal fun isSecretEnvironmentName(name: String): Boolean {
    val upper = name.uppercase()
    return listOf("KEY", "TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL", "AUTH").any { it in upper }
}
