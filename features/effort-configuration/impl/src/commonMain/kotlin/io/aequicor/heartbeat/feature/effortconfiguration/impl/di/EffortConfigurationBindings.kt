package io.aequicor.heartbeat.feature.effortconfiguration.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfiguration
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationIntent
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationMachineSpec
import io.aequicor.heartbeat.feature.effortconfiguration.impl.domain.EffortChoices
import io.aequicor.heartbeat.feature.effortconfiguration.impl.domain.EffortConfigurationEffects
import kotlinx.coroutines.launch

private val log = Log.tag("EffortConfigurationBindings")

/** The effort machine belongs to the profile; it is created and started on first injection. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object EffortConfigurationBindings {
    /**
     * The only binding other features inject: state without the ability to send internal intents. The machine
     * itself is not bound, so it is reachable only through `MachineRegistry` with `Public` intents.
     */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun view(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        store: EffortChoices,
        toggles: FeatureToggles,
    ): EffortChoicesView {
        val machine = launcher.launch(
            EffortConfigurationMachineSpec,
            scope,
            EffortConfigurationEffects(store) { toggles.get(EffortConfiguration) },
        )
        scope.coroutineScope.launch {
            val result = machine.send(EffortConfigurationIntent.Public.Start)
            log.i { "effort configuration start: $result" }
        }
        return object : EffortChoicesView {
            override val state = machine.state
        }
    }
}

/** Registers the effort toggle in the toggles panel. */
@ContributesTo(AppScope::class)
@BindingContainer
object EffortConfigurationToggleBindings {
    /** The type must be exactly `FeatureToggle<*>` to join the registry set. */
    @Provides
    @IntoSet
    fun effortConfiguration(): FeatureToggle<*> = EffortConfiguration
}
