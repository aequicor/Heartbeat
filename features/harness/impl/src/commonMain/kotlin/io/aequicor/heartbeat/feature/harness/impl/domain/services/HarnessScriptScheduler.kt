package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.script.ScriptRegistration
import io.aequicor.heartbeat.feature.harness.api.script.ScriptScheduler
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessTimers
import io.aequicor.heartbeat.feature.scheduler.api.EventKey
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import kotlin.time.Duration
import kotlin.time.Instant

/** Complete instance-owned scheduler API. Only timer declarations are accepted before publication. */
internal class HarnessScriptScheduler(
    private val owner: HarnessInstanceTarget,
    private val timers: HarnessTimers,
    private val host: HarnessSchedulerHost,
    private val origins: HarnessCallOrigins,
) : ScriptScheduler {
    override fun every(interval: Duration, handler: suspend () -> Unit): ScriptRegistration = timers.every(
        interval,
        handler,
    )
    override fun at(instant: Instant, handler: suspend () -> Unit): ScriptRegistration = timers.at(instant, handler)
    override suspend fun wake(session: SessionRef, condition: WakeCondition, note: String): WakeId {
        val origin = origins.current()
        return host.wake(owner, HarnessScriptWake(session, condition, note, origin))
    }
    override suspend fun cancel(id: WakeId): Boolean {
        val origin = origins.current()
        return host.cancel(owner, id, origin)
    }
    override suspend fun publish(name: ItemName, payload: String?): EventKey {
        val origin = origins.current()
        return host.publish(owner, HarnessScriptEvent(name, payload, origin))
    }
    fun tryCommitPublication(): Boolean = timers.tryCommitPublication()
    fun close() = timers.close()
}

/** Creates private registrations only; it must not open a session or publish events during construction. */
internal fun interface HarnessScriptSchedulerFactory {
    fun create(request: HarnessActivationRequest, access: HarnessInstanceAccess): HarnessScriptScheduler
}
