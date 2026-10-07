package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.event.EngineEvent
import io.aequicor.heartbeat.feature.harness.api.event.HarnessBusEvent
import io.aequicor.heartbeat.feature.harness.api.event.HarnessEvent
import io.aequicor.heartbeat.feature.harness.api.event.HarnessLifecycleEvent
import io.aequicor.heartbeat.feature.harness.api.event.SchedulerEvent
import io.aequicor.heartbeat.feature.harness.api.event.SessionEvent
import io.aequicor.heartbeat.feature.harness.api.event.SystemEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Bounded, nonblocking event ingress. Each published script owns one consumer in its background scope; disposal,
 * detachment and generation retirement are rechecked at actual execution. Queue envelopes preserve trusted
 * ancestry independently of untrusted event payload. No callback runs on the event producer's coroutine.
 */
internal class HarnessEventDispatch(
    private val runtime: HarnessRuntime,
    private val sessions: HarnessSessionAdmission,
) {
    private val queues = MutableStateFlow<Map<HarnessInstance, Channel<Envelope>>>(emptyMap())
    private val log = Log.tag("HarnessRuntime")

    @HighFrequency
    suspend fun emit(event: HarnessEvent, origin: HarnessCallOrigin = HarnessCallOrigin()) {
        log.v { "enqueue harness event" }
        runtime.published().forEach { instance ->
            val context = instance.runtimeContext as? HarnessScriptContext ?: return@forEach
            if (!instance.isActive || !accepts(instance, event)) return@forEach
            if (context.registrations.events().any { it.callback.isActive && it.type.isInstance(event) }) {
                queue(instance).trySend(Envelope(event, origin))
            }
        }
    }

    @HighFrequency
    private fun queue(instance: HarnessInstance): Channel<Envelope> {
        log.v { "attach instance event queue" }
        while (true) {
            val previous = queues.value
            previous[instance]?.let { return it }
            val channel = Channel<Envelope>(64, BufferOverflow.DROP_OLDEST)
            if (!queues.compareAndSet(previous, previous + (instance to channel))) {
                channel.cancel()
                continue
            }
            instance.dispatchScope.launch {
                for (envelope in channel) {
                    if (!instance.isActive) break
                    deliver(instance, envelope)
                }
            }.invokeOnCompletion {
                channel.cancel()
                queues.update { current -> current - instance }
            }
            return channel
        }
    }

    @HighFrequency
    private suspend fun deliver(instance: HarnessInstance, envelope: Envelope) {
        val context = instance.runtimeContext as? HarnessScriptContext ?: return
        context.registrations.events().forEach { registration ->
            val callback = registration.callback
            if (callback.isActive && registration.type.isInstance(
                    envelope.event,
                ) && accepts(instance, envelope.event)
            ) {
                runtime.invoke(
                    instance,
                    HarnessInvocationBudget.Event,
                    HarnessInvocationOptions(origin = envelope.origin.merge(callback.origin)),
                ) {
                    if (accepts(instance, envelope.event)) callback.acquire()?.invoke(envelope.event)
                }
            }
        }
    }

    @HighFrequency
    private fun accepts(instance: HarnessInstance, event: HarnessEvent): Boolean = when (event) {
        is SessionEvent -> sessions.allows(instance.request.harness.id, event.context.session)

        is SchedulerEvent.WakeScheduled -> sessions.allows(instance.request.harness.id, event.session)

        is SchedulerEvent.Woke -> sessions.allows(instance.request.harness.id, event.session)

        is HarnessLifecycleEvent.Activated -> instance.request.harness.id == event.harness

        is HarnessLifecycleEvent.WorkflowFinished -> instance.request.harness.id == event.harness

        is EngineEvent, is SystemEvent, is HarnessBusEvent,
        is SchedulerEvent.WakeFailed, is SchedulerEvent.WakeRejected, is SchedulerEvent.WakesCancelled,
        -> true
    }

    private data class Envelope(val event: HarnessEvent, val origin: HarnessCallOrigin) {
        override fun toString(): String = "HarnessEventEnvelope(***)"
    }
}
