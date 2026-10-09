package io.aequicor.heartbeat.feature.harness.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.impl.data.events.HarnessEventAncestry
import io.aequicor.heartbeat.feature.harness.impl.data.events.HarnessEventInputFactory
import io.aequicor.heartbeat.feature.harness.impl.data.events.HarnessEventSourcePorts
import io.aequicor.heartbeat.feature.harness.impl.data.events.HarnessEventSources
import io.aequicor.heartbeat.feature.harness.impl.data.events.HarnessProjectSnapshots
import io.aequicor.heartbeat.feature.harness.impl.data.events.HarnessSessionHook
import io.aequicor.heartbeat.feature.harness.impl.data.events.HarnessSessionHookDispatchers
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessEnabledWork
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessEventDispatch
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessEventGate
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessHookDispatch
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestAncestry
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRuntime
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessSessionProofs
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessToolDispatch
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import kotlin.time.Clock

/** Builds only ports until enable/first callback; no constructor dereferences the facade or library lazies. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object HarnessEventBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun gate(): HarnessEventGate = HarnessEventGate()

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun projects(): HarnessProjectSnapshots = HarnessProjectSnapshots()

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun origins(ancestry: HarnessRequestAncestry): HarnessRequestOrigins = HarnessRequestOrigins(ancestry)

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun proofs(
        machine: Lazy<HarnessMachine>,
        registry: MachineRegistry,
        projects: HarnessProjectSnapshots,
        gate: HarnessEventGate,
    ): HarnessSessionProofs = HarnessSessionProofs(
        { if (gate.isEnabled) machine.value.state.value else HarnessState.Idle(isSuspended = true) },
        { registry.find(WorktreeMachineKey)?.state?.value },
        { projects.projects.value },
    )

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun events(
        runtime: HarnessRuntime,
        proofs: HarnessSessionProofs,
        gate: HarnessEventGate,
    ): HarnessEventDispatch = HarnessEventDispatch(runtime, proofs) { gate.currentEpoch }

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun hooks(runtime: HarnessRuntime, proofs: HarnessSessionProofs): HarnessHookDispatch =
        HarnessHookDispatch(runtime, proofs)

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun tools(runtime: HarnessRuntime, proofs: HarnessSessionProofs): HarnessToolDispatch =
        HarnessToolDispatch(runtime, proofs)

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun sourcePorts(
        runtime: HarnessRuntime,
        events: HarnessEventDispatch,
        gate: HarnessEventGate,
        clock: Clock,
        origins: HarnessCallOrigins,
    ): HarnessEventSourcePorts = HarnessEventSourcePorts(runtime, events, gate, clock, origins)

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun enabledWork(
        factory: HarnessEventInputFactory,
        ports: HarnessEventSourcePorts,
        ancestry: HarnessEventAncestry,
    ): HarnessEnabledWork = HarnessEventSources(factory::create, ports, ancestry)

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun dispatchers(
        hooks: Lazy<HarnessHookDispatch>,
        events: Lazy<HarnessEventDispatch>,
        inputs: Lazy<HarnessEventInputFactory>,
        proofs: Lazy<HarnessSessionProofs>,
    ): HarnessSessionHookDispatchers = HarnessSessionHookDispatchers(hooks, events) {
        inputs.value.awaitProof(it, proofs.value)
    }

    @Provides @IntoSet
    internal fun hook(
        gate: HarnessEventGate,
        proofs: Lazy<HarnessSessionProofs>,
        dispatchers: HarnessSessionHookDispatchers,
        origins: Lazy<HarnessRequestOrigins>,
        clock: Clock,
    ): SessionHook = HarnessSessionHook(gate, proofs, dispatchers, origins, clock)
}
