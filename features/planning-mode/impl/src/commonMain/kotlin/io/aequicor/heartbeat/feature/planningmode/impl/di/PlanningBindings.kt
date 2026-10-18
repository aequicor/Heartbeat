package io.aequicor.heartbeat.feature.planningmode.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.planningmode.api.PlanningIntent
import io.aequicor.heartbeat.feature.planningmode.api.PlanningMachineSpec
import io.aequicor.heartbeat.feature.planningmode.api.PlanningModeEnabled
import io.aequicor.heartbeat.feature.planningmode.api.PlanningView
import io.aequicor.heartbeat.feature.planningmode.impl.domain.PlanFiles
import io.aequicor.heartbeat.feature.planningmode.impl.domain.PlanIndexStore
import io.aequicor.heartbeat.feature.planningmode.impl.domain.PlanningEffects
import io.aequicor.heartbeat.feature.planningmode.impl.domain.PlanningMachine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** The planning machine belongs to the profile (ProfileScope); [PlanningStartup] starts it with the feature. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object PlanningBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        index: PlanIndexStore,
        files: PlanFiles,
        clock: Clock,
    ): PlanningMachine = launcher.launch(PlanningMachineSpec, scope, PlanningEffects(index, files, clock))

    /**
     * The only binding other features inject: state without the ability to send internal intents. The machine itself
     * is reachable only through `MachineRegistry` with `Public` intents.
     */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun view(machine: PlanningMachine): PlanningView = object : PlanningView {
        override val state = machine.state
    }
}

/**
 * Loads plans as soon as the profile opens with [PlanningModeEnabled] on, so a planning chat is read-only again
 * right after a restart. Storage stays untouched while the toggle is off.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class PlanningStartup(
    private val machine: Lazy<PlanningMachine>,
    private val toggles: FeatureToggles,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : ProfileStartup {
    private val log = Log.tag("PlanningStartup")

    override fun start() {
        profile.coroutineScope.launch {
            toggles.observe(PlanningModeEnabled).first { it }
            val result = machine.value.send(PlanningIntent.Public.Start)
            log.i { "planning start: $result" }
        }
    }
}

/** Registers the planning toggle in the toggles panel. */
@ContributesTo(AppScope::class)
@BindingContainer
object PlanningToggleBindings {
    /** The type must be exactly `FeatureToggle<*>` to join the registry set. */
    @Provides
    @IntoSet
    fun enabled(): FeatureToggle<*> = PlanningModeEnabled
}
