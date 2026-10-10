package io.aequicor.heartbeat.feature.scheduler.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerActions
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEnabled
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerMachineSpec
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerTaskGraphs
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphEffect
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphIntent
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphMachineSpec
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCapacityRecoverySource
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledEventOwner
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledHelperPromptOwner
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledRequestOriginObserver
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeOwner
import io.aequicor.heartbeat.feature.scheduler.api.spi.SchedulerEventSource
import io.aequicor.heartbeat.feature.scheduler.impl.data.BackgroundActions
import io.aequicor.heartbeat.feature.scheduler.impl.data.TaskGraphDriver
import io.aequicor.heartbeat.feature.scheduler.impl.data.TaskGraphMachine
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerEffects
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerMachine
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerPersistence
import io.aequicor.heartbeat.feature.scheduler.impl.domain.WakeDriver
import io.aequicor.heartbeat.feature.scheduler.impl.domain.WakeStorage
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** The scheduler machine belongs to the profile, so pending wakes outlive every screen. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object SchedulerBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun taskGraphMachine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        persistence: EffectHandler<TaskGraphEffect, TaskGraphIntent>,
    ): TaskGraphMachine = launcher.launch(TaskGraphMachineSpec, scope, persistence)

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun persistence(storage: WakeStorage): SchedulerPersistence = SchedulerPersistence(storage)

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        effects: SchedulerEffects,
    ): SchedulerMachine = launcher.launch(SchedulerMachineSpec, scope, effects)

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun effects(
        persistence: SchedulerPersistence,
        hosts: Lazy<Set<ScheduledSessionHost>>,
        owners: Lazy<Set<ScheduledWakeOwner>>,
        eventOwners: Lazy<Set<ScheduledEventOwner>>,
    ): SchedulerEffects = SchedulerEffects(persistence, hosts, owners, eventOwners)

    @Provides
    internal fun driver(
        machine: SchedulerMachine,
        bus: SchedulerBus,
        toggles: FeatureToggles,
        clock: Clock,
        sources: Set<SchedulerEventSource>,
    ): WakeDriver = WakeDriver(machine, bus, toggles.observe(SchedulerEnabled), clock, sources)
}

/** Session hosts and platform sources may be absent (mobile, bundles without a chat host). */
@ContributesTo(ProfileScope::class)
public interface SchedulerMultibindings {
    /** Hosts that own sessions and deliver wake prompts through their normal turn lifecycle. */
    @Multibinds(allowEmpty = true)
    public fun scheduledSessionHosts(): Set<ScheduledSessionHost>

    /** Durable helper slots, read before any new background admission. */
    @Multibinds(allowEmpty = true)
    public fun helperCapacityRecoverySources(): Set<HelperCapacityRecoverySource>

    /** Feature-owned admission controllers; resolved lazily only for owned wake delivery. */
    @Multibinds(allowEmpty = true)
    public fun scheduledWakeOwners(): Set<ScheduledWakeOwner>

    /** Publisher controllers and observers; resolved only for Feature or exact Session/HostTurn delivery. */
    @Multibinds(allowEmpty = true)
    public fun scheduledEventOwners(): Set<ScheduledEventOwner>

    /** Helper prompt context owners; the host resolves these only for explicit handoff or exact initiators. */
    @Multibinds(allowEmpty = true)
    public fun scheduledHelperPromptOwners(): Set<ScheduledHelperPromptOwner>

    /** Exact request provenance, resolved only for host turns carrying saved causal references. */
    @Multibinds(allowEmpty = true)
    public fun scheduledRequestOriginObservers(): Set<ScheduledRequestOriginObserver>

    /** Platform signal sources. */
    @Multibinds(allowEmpty = true)
    public fun schedulerEventSources(): Set<SchedulerEventSource>
}

/**
 * Starts the scheduler with the profile: loads stored wakes, feeds the machine with events and deadlines and reports
 * background actions a previous run left unfinished.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class SchedulerStartup(
    private val machine: Lazy<SchedulerMachine>,
    private val driver: Lazy<WakeDriver>,
    private val actions: Lazy<BackgroundActions>,
    private val graphs: Lazy<TaskGraphDriver>,
    @ForScope(ProfileScope::class) private val scope: ScopeHandle,
) : ProfileStartup {
    override fun start() {
        val scheduler = machine.value
        scope.coroutineScope.launch { scheduler.send(SchedulerIntent.Internal.Start) }
        driver.value.start(scope.coroutineScope)
        graphs.value.start()
        // Cleanup and quota recovery are required even while wake delivery is disabled.
        scope.coroutineScope.launch { actions.value.start() }
    }
}

/** Registers the scheduler toggles in the toggles panel. */
@ContributesTo(AppScope::class)
@BindingContainer
public object SchedulerToggleBindings {
    /** The type must be exactly `FeatureToggle<*>` to join the registry set. */
    @Provides
    @IntoSet
    public fun scheduler(): FeatureToggle<*> = SchedulerEnabled

    /** The type must be exactly `FeatureToggle<*>` to join the registry set. */
    @Provides
    @IntoSet
    public fun schedulerActions(): FeatureToggle<*> = SchedulerActions

    /** Graph execution is separately gated while the existing scheduler remains compatible. */
    @Provides
    @IntoSet
    public fun schedulerTaskGraphs(): FeatureToggle<*> = SchedulerTaskGraphs
}
