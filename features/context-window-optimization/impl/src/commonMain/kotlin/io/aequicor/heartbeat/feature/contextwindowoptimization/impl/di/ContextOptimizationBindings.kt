package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.di

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
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.AutoCompactionEnabled
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationEnabled
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationIntent
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationMachineSpec
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationView
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextRolloverEnabled
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.domain.ContextOptimizationEffects
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.domain.ContextSettingsStore
import kotlinx.coroutines.launch

private val log = Log.tag("ContextOptimizationBindings")

/** The context optimization machine belongs to the profile; it is created and started on first injection. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object ContextOptimizationBindings {
    /**
     * The only binding other features inject: state without the ability to send internal intents. The machine itself
     * is reachable only through `MachineRegistry` with `Public` intents.
     */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun view(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        store: ContextSettingsStore,
    ): ContextOptimizationView {
        val machine = launcher.launch(ContextOptimizationMachineSpec, scope, ContextOptimizationEffects(store))
        scope.coroutineScope.launch {
            val result = machine.send(ContextOptimizationIntent.Public.Start)
            log.i { "context optimization start: $result" }
        }
        return object : ContextOptimizationView {
            override val state = machine.state
        }
    }
}

/** Registers the context optimization toggles in the toggles panel. */
@ContributesTo(AppScope::class)
@BindingContainer
object ContextOptimizationToggleBindings {
    /** The type must be exactly `FeatureToggle<*>` to join the registry set. */
    @Provides
    @IntoSet
    fun enabled(): FeatureToggle<*> = ContextOptimizationEnabled

    /** Automatic compaction by threshold. */
    @Provides
    @IntoSet
    fun autoCompaction(): FeatureToggle<*> = AutoCompactionEnabled

    /** Rollover into a new session. */
    @Provides
    @IntoSet
    fun rollover(): FeatureToggle<*> = ContextRolloverEnabled
}
