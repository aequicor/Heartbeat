package io.aequicor.heartbeat.feature.autocontinue.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueEnabled
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueHost
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueMachineSpec
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueState
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueView
import io.aequicor.heartbeat.feature.autocontinue.impl.domain.AutoContinueEffects
import io.aequicor.heartbeat.feature.autocontinue.impl.domain.AutoContinueMachine
import io.aequicor.heartbeat.feature.autocontinue.impl.domain.AutoContinueStore
import kotlinx.coroutines.launch
import kotlin.time.Clock

private val log = Log.tag("AutoContinueBindings")

/** The auto-continue machine belongs to the profile; it is created and started on first injection of the view. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object AutoContinueBindings {
    /** The machine; other features reach it through `MachineRegistry`, the feature's own screens directly. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        store: AutoContinueStore,
        prompts: IsolatedPrompts,
        hosts: Provider<Set<AutoContinueHost>>,
        toggles: FeatureToggles,
        clock: Clock,
    ): AutoContinueMachine {
        val effects = AutoContinueEffects(store, prompts, { hosts() }, { toggles.get(AutoContinueEnabled) }, clock)
        val machine = launcher.launch(AutoContinueMachineSpec, scope, effects)
        scope.coroutineScope.launch {
            val result = machine.send(AutoContinueIntent.Public.Start)
            log.i { "auto-continue start: $result" }
        }
        return machine
    }

    /** The only binding other features inject: state without the ability to send internal intents. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun view(machine: AutoContinueMachine): AutoContinueView = object : AutoContinueView {
        override val state = machine.state

        override suspend fun admit(action: suspend (AutoContinueState) -> Boolean): Boolean = machine.admit(action)
    }
}

/** Chat hosts that send autopilot replies; empty until the studio contributes one. */
@ContributesTo(ProfileScope::class)
interface AutoContinueMultibindings {
    /** Hosts of chats. */
    @Multibinds(allowEmpty = true)
    fun autoContinueHosts(): Set<AutoContinueHost>
}

/** Registers the auto-continue toggle in the toggles panel. */
@ContributesTo(AppScope::class)
@BindingContainer
object AutoContinueToggleBindings {
    /** The type must be exactly `FeatureToggle<*>` to join the registry set. */
    @Provides
    @IntoSet
    fun autoContinue(): FeatureToggle<*> = AutoContinueEnabled
}
