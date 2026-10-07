package io.aequicor.heartbeat.feature.harness.api.script

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.scheduler.api.EventKey
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Activation-owned timers and harness-owned durable session wakes.
 * Implementations assign ownership, origin and delivery ids.
 */
public interface ScriptScheduler {
    /**
     * Registers a process-local timer, recreated on activation.
     * The finite interval must be at least [HarnessLimits.MIN_TIMER]. It starts after publication and then after
     * each callback actually stops, including after timeout; callbacks of one timer never overlap. All items of
     * one harness share [HarnessLimits.TIMERS] registrations.
     */
    public fun every(interval: Duration, handler: suspend () -> Unit): ScriptRegistration

    /**
     * Registers one process-local deadline; it does not survive unload. A past deadline fires once after
     * publication. Disposing either kind of timer prevents future callbacks but lets an admitted callback finish.
     */
    public fun at(instant: Instant, handler: suspend () -> Unit): ScriptRegistration

    /**
     * Schedules a session visible to this harness, subject to harness wake quotas and live owner admission.
     * [note] is private, fenced data. The host fixes the harness owner; scripts cannot impersonate another owner.
     */
    public suspend fun wake(session: SessionRef, condition: WakeCondition, note: String): WakeId

    /** Cancels only a pending wake owned by this harness; false does not prove a native turn stopped. */
    public suspend fun cancel(id: WakeId): Boolean

    /**
     * Publishes untrusted payload under custom.harness.<immutable harness slug>.<name>.
     * No other namespace is writable.
     */
    public suspend fun publish(name: ItemName, payload: String? = null): EventKey
}
