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
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerActions
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEnabled
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerMachineSpec
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeOwner
import io.aequicor.heartbeat.feature.scheduler.api.spi.SchedulerEventSource
import io.aequicor.heartbeat.feature.scheduler.impl.data.BackgroundActions
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerEffects
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerMachine
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerPersistence
import io.aequicor.heartbeat.feature.scheduler.impl.domain.WakeDriver
import io.aequicor.heartbeat.feature.scheduler.impl.domain.WakeStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** The scheduler machine belongs to the profile, so pending wakes outlive every screen. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object SchedulerBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun persistence(storage: WakeStorage): SchedulerPersistence = SchedulerPersistence(storage)

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        persistence: SchedulerPersistence,
        hosts: Lazy<Set<ScheduledSessionHost>>,
        owners: Lazy<Set<ScheduledWakeOwner>>,
    ): SchedulerMachine = launcher.launch(SchedulerMachineSpec, scope, SchedulerEffects(persistence, hosts, owners))

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

    /** Feature-owned admission controllers; resolved lazily only for owned wake delivery. */
    @Multibinds(allowEmpty = true)
    public fun scheduledWakeOwners(): Set<ScheduledWakeOwner>

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
    private val toggles: FeatureToggles,
    private val machine: Lazy<SchedulerMachine>,
    private val driver: Lazy<WakeDriver>,
    private val actions: Lazy<BackgroundActions>,
    @ForScope(ProfileScope::class) private val scope: ScopeHandle,
) : ProfileStartup {
    override fun start() {
        val scheduler = machine.value
        scope.coroutineScope.launch { scheduler.send(SchedulerIntent.Internal.Start) }
        driver.value.start(scope.coroutineScope)
        // Background actions (and the engine runtime behind them) are built only when the scheduler is on.
        scope.coroutineScope.launch {
            toggles.observe(SchedulerEnabled).first { it }
            actions.value.start()
        }
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
}
