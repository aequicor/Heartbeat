package io.aequicor.heartbeat.feature.adaptivesubagents.impl.di

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
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveSubagentsEnabled
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveSubagentsResearch
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveSubagentsRouter
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveSubagentsSessionTools
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesIntent
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesMachineSpec
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesView
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.LibraryMachine
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.ModelProfileStore
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.ModelProfilesEffects
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.research.ModelResearch
import kotlinx.coroutines.launch

private val log = Log.tag("AdaptiveSubagentsBindings")

/** The library machine belongs to the profile; it is created and started on the first injection of its view. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object AdaptiveSubagentsBindings {
    /** The library machine; other features read it only through [ModelProfilesView]. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        store: ModelProfileStore,
        researcher: ModelResearch,
        toggles: FeatureToggles,
    ): LibraryMachine {
        val effects = ModelProfilesEffects(store, researcher) { toggles.get(AdaptiveSubagentsResearch) }
        val machine = launcher.launch(ModelProfilesMachineSpec, scope, effects)
        scope.coroutineScope.launch {
            val result = machine.send(ModelProfilesIntent.Public.Start)
            log.i { "model library start: $result" }
        }
        return machine
    }

    /** The only library binding other features inject: state without the ability to send internal intents. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun view(machine: LibraryMachine): ModelProfilesView = object : ModelProfilesView {
        override val state = machine.state
    }
}

/** Registers the feature's toggles in the toggles panel. */
@ContributesTo(AppScope::class)
@BindingContainer
object AdaptiveSubagentsToggleBindings {
    /** The type must be exactly `FeatureToggle<*>` to join the registry set. */
    @Provides
    @IntoSet
    fun enabled(): FeatureToggle<*> = AdaptiveSubagentsEnabled

    /** Automatic model descriptions. */
    @Provides
    @IntoSet
    fun research(): FeatureToggle<*> = AdaptiveSubagentsResearch

    /** The LLM router of selection. */
    @Provides
    @IntoSet
    fun router(): FeatureToggle<*> = AdaptiveSubagentsRouter

    /** Subagent tools of ordinary chats. */
    @Provides
    @IntoSet
    fun sessionTools(): FeatureToggle<*> = AdaptiveSubagentsSessionTools
}
