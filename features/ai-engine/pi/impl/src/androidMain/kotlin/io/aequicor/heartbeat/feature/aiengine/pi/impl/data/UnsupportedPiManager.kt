package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext

/** Pi runs only on the desktop: nothing is installed here and nothing can be. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class UnsupportedPiManager : PiEngineManager {
    override suspend fun inspect(launch: LaunchContext): Installation = Installation(InstallSource.Missing)
}
