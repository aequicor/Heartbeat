package io.aequicor.heartbeat.feature.scheduler.api.spi

import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskResult

/**
 * Optional graph execution on a chat host. Preparation creates an empty chat; the scheduler persists its opaque
 * ID before calling [runTask]. Runs retain the normal transcript and permission UI. [previousExecution] on a run
 * means reconcile that submission first, adopting a live turn or its result before considering a continuation.
 */
public interface ScheduledTaskHost : ScheduledSessionHost {
    /** Creates an empty helper chat without submitting a turn. */
    public suspend fun prepareTask(request: SpawnRequest): String?

    /** Executes or recovers an assignment in the previously prepared chat, and returns its authoritative result. */
    public suspend fun runTask(
        hostTask: String,
        request: SpawnRequest,
        previousExecution: String?,
        admission: kotlinx.coroutines.flow.Flow<Boolean>,
    ): GraphTaskResult

    /** Requests cancellation of this assignment; true means the native execution is confirmed stopped. */
    public suspend fun stopTask(hostTask: String): Boolean
}
