package io.aequicor.heartbeat.feature.harness.impl.di

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.followHarnessToggle
import kotlinx.coroutines.launch

/** Only the observer starts with the profile; the library and its storage are lazy until first enable. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class HarnessStartup(
    private val machine: Lazy<HarnessMachine>,
    private val toggles: FeatureToggles,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : ProfileStartup {
    override fun start() {
        profile.coroutineScope.launch { followHarnessToggle(machine, toggles.observe(HarnessEnabled)) }
    }
}
