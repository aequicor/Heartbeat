package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import kotlin.time.Instant

/** Host scheduler access. Unknown outcomes must not be retried as a new immutable wake. */
internal interface HarnessWakePort {
    fun snapshot(): SchedulerState.Ready?
    suspend fun schedule(request: WakeRequest, at: Instant): HarnessWakeReceipt
    suspend fun cancel(id: WakeId, cause: EventOrigin? = null): Boolean
}

internal enum class HarnessWakeReceipt { Scheduled, Rejected, Unknown }
