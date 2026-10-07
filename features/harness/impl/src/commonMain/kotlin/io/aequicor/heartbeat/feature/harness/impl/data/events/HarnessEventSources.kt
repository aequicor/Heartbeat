package io.aequicor.heartbeat.feature.harness.impl.data.events

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.harness.api.event.HarnessEvent
import io.aequicor.heartbeat.feature.harness.api.event.HarnessLifecycleEvent
import io.aequicor.heartbeat.feature.harness.api.event.SystemEvent
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessEnabledWork
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessEventDispatch
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessEventGate
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstance
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRuntime
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.harnessScriptFailure
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

/** Enabled-lifetime streams. Creating them may resolve lazy facade ports, but cannot probe or start an engine. */
internal data class HarnessEventInputs(
    val bus: Flow<BusEvent>,
    val scheduler: HarnessEventSubscription<SchedulerOutput>,
    val schedulerEnabled: Flow<Boolean>,
    val engines: Flow<List<EngineInfo>>,
    val bindings: Flow<List<EngineBinding>>,
    val workflows: HarnessEventSubscription<HarnessLifecycleEvent.WorkflowFinished>,
    val prepare: (CoroutineScope) -> Unit,
) {
    override fun toString(): String = "HarnessEventInputs(***)"
}

/** Source assembly shared with the session hook; the owner closes gate before cancelling this branch. */
internal data class HarnessEventSourcePorts(
    val runtime: HarnessRuntime,
    val dispatch: HarnessEventDispatch,
    val gate: HarnessEventGate,
    val clock: Clock,
    val origins: HarnessCallOrigins,
) {
    override fun toString(): String = "HarnessEventSourcePorts(***)"
}

/**
 * Installs hot observers before library Start/Resumed. Each enable creates fresh diff baselines and a fresh
 * system epoch. Only host-created envelopes carry execution ancestry; no collector inherits arbitrary script
 * origin. Buffered execution remains instance-owned and rechecks admission after these producers stop.
 */
internal class HarnessEventSources(
    private val inputs: () -> HarnessEventInputs,
    private val ports: HarnessEventSourcePorts,
    private val ancestry: HarnessEventAncestry,
) : HarnessEnabledWork {
    private val log = Log.tag("HarnessRuntime")

    override fun start(scope: CoroutineScope) {
        log.v { "start harness event sources" }
        val owned = CoroutineScope(scope.coroutineContext + ports.origins.context(HarnessCallOrigin()))
        val streams = inputs()
        streams.prepare(owned)
        val scheduling = MutableStateFlow(false)
        ports.gate.open()
        owned.launch(start = CoroutineStart.UNDISPATCHED) {
            streams.schedulerEnabled.collect { scheduling.value = it }
        }
        owned.launch(start = CoroutineStart.UNDISPATCHED) {
            streams.bus.collect { event ->
                val epoch = ports.gate.currentEpoch ?: return@collect
                forward(event.harnessEvent(), epoch) { ancestry.origin(event.origin) }
                if (scheduling.value) event.networkEvent()?.let { ports.dispatch.emit(it, HarnessCallOrigin(), epoch) }
            }
        }
        streams.scheduler.subscribe(owned) { output ->
            if (ports.gate.isEnabled) {
                output.harnessEvent(ports.clock.now())?.let { projected ->
                    forward(projected) { ancestry.output(output) }
                }
            }
        }
        observeEngines(owned, streams)
        streams.workflows.subscribe(owned) {
            if (ports.gate.isEnabled) ports.dispatch.emit(it, HarnessCallOrigin())
        }
        observePublished(owned)
        observeStarted(owned, scheduling)
    }

    @HighFrequency
    private suspend fun forward(
        event: HarnessEvent,
        admittedEpoch: Long? = ports.gate.currentEpoch,
        resolve: suspend () -> HarnessCallOrigin,
    ) {
        val epoch = admittedEpoch ?: return
        val origin = try {
            resolve()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(harnessScriptFailure(error)) { "Skip event with unavailable request ancestry" }
            return
        }
        currentCoroutineContext().ensureActive()
        if (ports.gate.currentEpoch == epoch) ports.dispatch.emit(event, origin, epoch)
    }

    @HighFrequency
    private fun observeEngines(scope: CoroutineScope, streams: HarnessEventInputs) {
        val changes = HarnessEngineChanges()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            streams.engines.collect { current ->
                if (ports.gate.isEnabled) {
                    changes.engines(
                        current,
                        ports.clock.now(),
                    ).forEach { ports.dispatch.emit(it) }
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            streams.bindings.collect { current ->
                if (ports.gate.isEnabled) {
                    changes.connections(
                        current,
                        ports.clock.now(),
                    ).forEach { ports.dispatch.emit(it) }
                }
            }
        }
    }

    override fun stop() {
        log.v { "stop harness event admission" }
        ports.gate.close()
    }

    @HighFrequency
    private fun observePublished(scope: CoroutineScope) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var previous = emptySet<HarnessInstance>()
            ports.runtime.publishedInstances.collect { current ->
                if (ports.gate.isEnabled) {
                    current.filter { it !in previous }.forEach { instance ->
                        val request = instance.request
                        ports.dispatch.emit(
                            HarnessLifecycleEvent.Activated(
                                request.harness.id,
                                request.item.id,
                                request.harness.revision,
                                ports.clock.now(),
                            ),
                        )
                    }
                }
                previous = current.toSet()
            }
        }
    }

    @HighFrequency
    private fun observeStarted(scope: CoroutineScope, scheduling: Flow<Boolean>) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var startedAt: Instant? = null
            var delivered = emptySet<HarnessInstance>()
            combine(ports.runtime.publishedInstances, scheduling) { instances, enabled -> instances to enabled }
                .collect { (instances, enabled) ->
                    if (!enabled || !ports.gate.isEnabled) {
                        startedAt = null
                        delivered = emptySet()
                    } else {
                        val at = startedAt ?: ports.clock.now().also { startedAt = it }
                        instances.filter { it !in delivered }.forEach {
                            ports.dispatch.emitTo(it, SystemEvent.Started(at))
                        }
                        delivered = instances.toSet()
                    }
                }
        }
    }
}
