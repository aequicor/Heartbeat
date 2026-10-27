package io.aequicor.heartbeat.feature.appupdate.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.AppStartup
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateBetaChannel
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateEnabled
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateIntent
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateOutput
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours

/**
 * Starts the update machine with the launch and asks it to check at once and every [CHECK_INTERVAL] while updates
 * are switched on; switching them off forgets the offer. A change of the beta channel asks again at once. A launch
 * that cannot update itself stops asking, and a busy machine (checking, installing, restarting) skips a tick, so
 * routine ticks never reach it as ignored intents.
 */
@ContributesIntoSet(AppScope::class)
@Inject
internal class AppUpdateStartup(
    private val machine: Lazy<Machine<AppUpdateState, AppUpdateIntent, AppUpdateOutput>>,
    private val toggles: FeatureToggles,
    @ForScope(AppScope::class) private val scope: ScopeHandle,
    private val dispatchers: DispatcherProvider,
) : AppStartup {
    override fun start() {
        val updates = machine.value
        scope.coroutineScope.launch(dispatchers.main) {
            toggles.observe(AppUpdateEnabled).collectLatest { isEnabled ->
                if (isEnabled) {
                    checkPeriodically(updates)
                } else {
                    deactivateWhenIdle(updates)
                }
            }
        }
    }

    private suspend fun deactivateWhenIdle(updates: Machine<AppUpdateState, AppUpdateIntent, AppUpdateOutput>) {
        // A started installation must finish. If it fails, discard its offer while the toggle is still off.
        // collectLatest cancels this wait when the user enables updates again.
        val idle = updates.state.first { it !is AppUpdateState.Installing && it !is AppUpdateState.Restarting }
        if (idle != AppUpdateState.Inactive) updates.send(AppUpdateIntent.Public.Deactivate)
    }

    private suspend fun checkPeriodically(updates: Machine<AppUpdateState, AppUpdateIntent, AppUpdateOutput>) =
        coroutineScope {
            // A changed channel offers other releases: ask again now instead of at the next tick.
            launch { toggles.observe(AppUpdateBetaChannel).drop(1).collect { recheckAfterChannelChange(updates) } }
            while (requestCheck(updates)) delay(CHECK_INTERVAL)
        }

    /**
     * A check that is running used the previous channel: wait for it to end, then ask again.
     * An install is left alone.
     */
    private suspend fun recheckAfterChannelChange(updates: Machine<AppUpdateState, AppUpdateIntent, AppUpdateOutput>) {
        if (updates.state.value is AppUpdateState.Checking) {
            updates.state.first { it !is AppUpdateState.Checking }
        }
        requestCheck(updates)
    }

    /** Asks for a check unless the machine is busy; false when this launch cannot update itself. */
    private suspend fun requestCheck(updates: Machine<AppUpdateState, AppUpdateIntent, AppUpdateOutput>): Boolean {
        when (updates.state.value) {
            is AppUpdateState.Unsupported -> return false

            is AppUpdateState.Checking, is AppUpdateState.Installing, is AppUpdateState.Restarting -> Unit

            AppUpdateState.Inactive, is AppUpdateState.UpToDate, is AppUpdateState.CheckFailed,
            is AppUpdateState.Available,
            -> updates.send(AppUpdateIntent.Public.Check)
        }
        return true
    }

    private companion object {
        val CHECK_INTERVAL = 6.hours
    }
}
