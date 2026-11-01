package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.domain.DeepSeekEngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext

/** The desktop CLI does not run here: nothing is installed and nothing can be. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class UnsupportedDeepSeekManager : DeepSeekEngineManager {
    override suspend fun inspect(launch: LaunchContext): Installation =
        Installation(InstallSource.Missing, isRunnable = false)
}
