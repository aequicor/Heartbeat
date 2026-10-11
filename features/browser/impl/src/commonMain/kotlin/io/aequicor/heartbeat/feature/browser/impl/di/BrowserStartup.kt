package io.aequicor.heartbeat.feature.browser.impl.di

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.browser.api.BrowserIntent
import io.aequicor.heartbeat.feature.browser.api.BrowserOutput
import io.aequicor.heartbeat.feature.browser.api.BrowserState
import kotlinx.coroutines.launch

/**
 * Starts availability observation independently of navigation; native initialization remains lazy.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class BrowserStartup(
    private val machine: Lazy<Machine<BrowserState, BrowserIntent, BrowserOutput>>,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : ProfileStartup {
    override fun start() {
        profile.coroutineScope.launch { machine.value.send(BrowserIntent.Public.Start) }
    }
}
