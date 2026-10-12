package io.aequicor.heartbeat.feature.scheduler.api.spi

import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskResult

/**
 * Optional graph execution on a chat host. Preparation creates an empty chat; the scheduler persists its opaque
 * ID before calling [runTask]. Runs retain the normal transcript and permission UI. [previousExecution] on a run
 * identifies the original submission in the current recovery lineage. Reconcile its latest journaled continuation
 * first, adopting a live turn or its result. The root stays stable across observer restarts;
 * an explicit retry resets it.
 */
public interface ScheduledTaskHost : ScheduledSessionHost {
    /** Creates an empty helper chat without submitting a turn. */
    public suspend fun prepareTask(request: SpawnRequest): String?

    /**
     * Executes or recovers an assignment and returns its authoritative result. Before every new native prompt,
     * durably relay [SpawnRequest.causes] through [ScheduledRequestOriginObserver] using the actual native target.
     * Observing already accepted native work must not resubmit it or change its original request identity.
     */
    public suspend fun runTask(
        hostTask: String,
        request: SpawnRequest,
        previousExecution: String?,
        admission: kotlinx.coroutines.flow.Flow<Boolean>,
    ): GraphTaskResult

    /** Requests cancellation of this assignment; true means the native execution is confirmed stopped. */
    public suspend fun stopTask(hostTask: String): Boolean
}
