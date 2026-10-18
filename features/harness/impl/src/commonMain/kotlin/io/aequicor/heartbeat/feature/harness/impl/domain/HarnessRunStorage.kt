package io.aequicor.heartbeat.feature.harness.impl.domain

import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun

/**
 * Durable workflow journals. Running entries never expire. Terminal entries keep their original completion
 * deadline (30 days) and only the newest 20 per harness survive, except process-owned journals protected until
 * their attempt is closed. Missing committed running or protected data fails closed.
 * Every operation recovers the private KV journal before reading; no driver or helper executes here.
 */
internal interface HarnessRunStorage {
    /** Restores every running journal and retained terminal result, repairing only proven expired metadata. */
    suspend fun load(): List<WorkflowRun>

    /**
     * Creates when [expectedGeneration] is null, otherwise compares the committed driver generation.
     * Returns false for stale writes. An exact retry succeeds without changing retention. Immutable run identity
     * and terminal outcomes cannot be replaced. A new driver must persist generation g+1 with expected g first.
     * Local memo steps also persist Prepared before Completed. Parallel drivers serialize latest-snapshot merges
     * and retry a rejected stale proposal against the latest journal, preserving every branch and terminal memo.
     * [HarnessStorageUncertain] means retry this proposal; it never proves a helper stopped or a run disappeared.
     */
    suspend fun save(run: WorkflowRun, expectedGeneration: Long?): Boolean

    /** One journal by id, without decoding the others; null when absent or expired. */
    suspend fun find(id: RunId): WorkflowRun? = load().singleOrNull { it.id == id }

    /**
     * Removes the exact initial record of a start the run machine definitively rejected. A record that a driver
     * has already advanced is kept and false is returned; an absent record counts as discarded.
     */
    suspend fun discard(run: WorkflowRun): Boolean

    /** Releases retention protection only after the process owner proves the attempt cannot be dispatched again. */
    suspend fun releaseProcess(id: RunId): Boolean = false

    /** Whether the terminal result of [id] has already reached the durable scheduler outbox. */
    suspend fun isPublished(id: RunId): Boolean

    /** Records outbox delivery of a terminal run, so a restart does not publish its finish again. */
    suspend fun markPublished(id: RunId)
}
