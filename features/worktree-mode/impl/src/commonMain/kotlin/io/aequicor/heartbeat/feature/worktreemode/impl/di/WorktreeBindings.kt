package io.aequicor.heartbeat.feature.worktreemode.impl.di

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
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineSpec
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeModeEnabled
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeOutput
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.impl.data.WorktreeEffects
import kotlinx.coroutines.launch

/** Profile ownership keeps worktree operations independent of screen lifetime. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object WorktreeBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        effects: WorktreeEffects,
    ): Machine<WorktreeState, WorktreeIntent, WorktreeOutput> = launcher.launch(WorktreeMachineSpec, scope, effects)
}

/** Starts restoration before any Studio or engine consumer needs the machine. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class WorktreeStartup(
    private val machine: Lazy<Machine<WorktreeState, WorktreeIntent, WorktreeOutput>>,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : ProfileStartup {
    override fun start() {
        profile.coroutineScope.launch {
            val result = machine.value.send(WorktreeIntent.Public.Start)
            Log.tag("WorktreeStartup").i { "Worktree startup result=$result" }
        }
    }
}

/** Registers the disabled-by-default worktree switch. */
@ContributesTo(AppScope::class)
@BindingContainer
public object WorktreeToggleBindings {
    /** Exact FeatureToggle wildcard joins the application registry. */
    @Provides
    @IntoSet
    public fun toggle(): FeatureToggle<*> = WorktreeModeEnabled
}
