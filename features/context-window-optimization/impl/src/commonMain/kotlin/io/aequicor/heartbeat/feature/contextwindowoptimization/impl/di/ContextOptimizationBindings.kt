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
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.AutoCompactionEnabled
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextLedgerEnabled
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationEnabled
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationIntent
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationMachineSpec
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationView
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextRolloverEnabled
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.KoogMaskingEnabled
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.data.ContextLedgerHook
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.domain.ContextOptimizationEffects
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.domain.ContextSettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock

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
        clock: Clock,
    ): ContextOptimizationView {
        val machine = launcher.launch(ContextOptimizationMachineSpec, scope, ContextOptimizationEffects(store, clock))
        scope.coroutineScope.launch {
            val result = machine.send(ContextOptimizationIntent.Public.Start)
            log.i { "context optimization start: $result" }
        }
        return object : ContextOptimizationView {
            override val state = machine.state
        }
    }

    /** Delivers the progress ledger to engines that keep their context natively. */
    @Provides
    @IntoSet
    internal fun ledgerHook(
        view: ContextOptimizationView,
        toggles: FeatureToggles,
        facade: Lazy<EngineFacade>,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
    ): SessionHook {
        val main = toggles.observe(ContextOptimizationEnabled)
        val isEnabled = combine(main, toggles.observe(ContextLedgerEnabled)) { isMain, isLedger -> isMain && isLedger }
            .stateIn(scope.coroutineScope, SharingStarted.Eagerly, false)
        return ContextLedgerHook(view, { isEnabled.value }) { session ->
            facade.value.engines.state.value.find { it.descriptor.id == session.engine }
                ?.descriptor?.areInstructionsRefreshedPerTurn == true
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

    /** Progress ledger and agent tools. */
    @Provides
    @IntoSet
    fun ledger(): FeatureToggle<*> = ContextLedgerEnabled

    /** Masking of older tool results in Koog prompts. */
    @Provides
    @IntoSet
    fun koogMasking(): FeatureToggle<*> = KoogMaskingEnabled
}
