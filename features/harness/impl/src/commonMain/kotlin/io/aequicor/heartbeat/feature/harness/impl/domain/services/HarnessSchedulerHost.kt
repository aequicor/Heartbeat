package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.scheduler.api.EventKey
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import kotlin.time.Instant

/** Host resolves trusted routing and current activation; script payload never grants ownership or authority. */
internal interface HarnessSchedulerHost {
    suspend fun wake(owner: HarnessInstanceTarget, request: HarnessScriptWake): WakeId
    suspend fun cancel(owner: HarnessInstanceTarget, id: WakeId, origin: HarnessCallOrigin): Boolean
    suspend fun publish(owner: HarnessInstanceTarget, event: HarnessScriptEvent): EventKey
}

internal data class HarnessScriptWake(
    val session: SessionRef,
    val condition: WakeCondition,
    val note: String,
    val origin: HarnessCallOrigin,
    val at: Instant,
) {
    override fun toString(): String = "HarnessScriptWake(***)"
}

internal data class HarnessScriptEvent(val name: ItemName, val payload: String?, val origin: HarnessCallOrigin) {
    override fun toString(): String = "HarnessScriptEvent(***)"
}
