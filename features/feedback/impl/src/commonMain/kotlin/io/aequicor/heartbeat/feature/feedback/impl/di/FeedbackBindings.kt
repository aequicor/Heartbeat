package io.aequicor.heartbeat.feature.feedback.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.feedback.api.FeedbackEnabled
import io.aequicor.heartbeat.feature.feedback.api.FeedbackIntent
import io.aequicor.heartbeat.feature.feedback.api.FeedbackMachineSpec
import io.aequicor.heartbeat.feature.feedback.api.FeedbackOutput
import io.aequicor.heartbeat.feature.feedback.api.FeedbackState
import io.aequicor.heartbeat.feature.feedback.impl.domain.FeedbackJournal
import io.aequicor.heartbeat.feature.feedback.impl.domain.FeedbackStorage
import kotlinx.coroutines.launch

/** The feedback machine belongs to the profile; its journal persists history across profile restarts. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object FeedbackBindings {
    @Provides
    internal fun journal(storage: FeedbackStorage): FeedbackJournal = FeedbackJournal(storage)

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
    ): Machine<FeedbackState, FeedbackIntent, FeedbackOutput> =
        launcher.launch(FeedbackMachineSpec, scope, EffectHandler.None)
}

/** Registers feedback before any screen and restores history independently of the rendering toggle. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class FeedbackStartup(
    private val machine: Lazy<Machine<FeedbackState, FeedbackIntent, FeedbackOutput>>,
    private val journal: Lazy<FeedbackJournal>,
    @ForScope(ProfileScope::class) private val scope: ScopeHandle,
    private val dispatchers: DispatcherProvider,
) : ProfileStartup {
    override fun start() {
        val feedback = machine.value
        scope.coroutineScope.launch(dispatchers.io) { journal.value.run(feedback) }
    }
}

/** Registers the feedback toggle in the profile controls. */
@ContributesTo(AppScope::class)
@BindingContainer
public object FeedbackToggleBindings {
    /** The exact result type joins the feature-toggle registry. */
    @Provides
    @IntoSet
    public fun feedback(): FeatureToggle<*> = FeedbackEnabled
}
