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
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateEnabled
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateIntent
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateOutput
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours

/**
 * Starts the update machine with the launch and asks it to check at once and every [CHECK_INTERVAL] while updates
 * are switched on; switching them off forgets the offer. The machine itself ignores checks while it installs.
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
                if (!isEnabled) {
                    updates.send(AppUpdateIntent.Public.Deactivate)
                    return@collectLatest
                }
                while (true) {
                    updates.send(AppUpdateIntent.Public.Check)
                    delay(CHECK_INTERVAL)
                }
            }
        }
    }

    private companion object {
        val CHECK_INTERVAL = 6.hours
    }
}
