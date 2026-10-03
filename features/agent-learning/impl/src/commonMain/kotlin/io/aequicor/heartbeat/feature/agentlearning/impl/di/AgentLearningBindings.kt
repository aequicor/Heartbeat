package io.aequicor.heartbeat.feature.agentlearning.impl.di

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
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningEnabled
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningIntent
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningMachineSpec
import io.aequicor.heartbeat.feature.agentlearning.impl.domain.AgentLearningEffects
import io.aequicor.heartbeat.feature.agentlearning.impl.domain.LearningMachine
import io.aequicor.heartbeat.feature.agentlearning.impl.domain.LearningStorage
import kotlinx.coroutines.launch

private val log = Log.tag("AgentLearningBindings")

/** The registry belongs to the profile; it is created and starts loading on first injection. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object AgentLearningBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        storage: LearningStorage,
    ): LearningMachine {
        val machine = launcher.launch(AgentLearningMachineSpec, scope, AgentLearningEffects(storage))
        scope.coroutineScope.launch {
            val result = machine.send(AgentLearningIntent.Public.Start)
            log.i { "agent learning start: $result" }
        }
        return machine
    }
}

/** Registers the learning toggle in the toggles panel. */
@ContributesTo(AppScope::class)
@BindingContainer
object AgentLearningToggleBindings {
    /** The type must be exactly `FeatureToggle<*>` to join the registry set. */
    @Provides
    @IntoSet
    fun agentLearning(): FeatureToggle<*> = AgentLearningEnabled
}
