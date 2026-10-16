package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ProjectFiles

/** Pi runs only on the desktop; its configuration is not offered here. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class UnsupportedPiConfigurator : PiConfigurator {
    override suspend fun readProject(directory: String): ProjectFiles = throw ConfigException(ConfigFailure.Unsupported)

    override suspend fun writeProject(
        directory: String,
        changes: Map<ConfigDocumentId, String?>,
        expected: Map<ConfigDocumentId, String?>,
    ): ProjectFiles = throw ConfigException(ConfigFailure.Unsupported)
}
