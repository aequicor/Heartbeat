package io.aequicor.heartbeat.feature.projectconfig.impl.data

import io.aequicor.heartbeat.feature.projectconfig.api.ConfigFile
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigReadFailure
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigBundles
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigRead
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigTree
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigWriteException
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigWriteFailure
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ProjectFolders

/** Platforms without local projects and file dialogs (Android, iOS): nothing is read or written. */
internal object UnsupportedConfigFiles : ProjectFolders, ConfigBundles {
    override val isAvailable: Boolean = false

    override suspend fun read(root: String): ConfigRead = ConfigRead.Failed(ConfigReadFailure.Unsupported)

    override suspend fun fingerprint(root: String): String? = null

    override suspend fun write(root: String, put: List<ConfigFile>, delete: List<ConfigPath>): Unit =
        throw ConfigWriteException(ConfigWriteFailure.Unsupported)

    override suspend fun write(path: String, tree: ConfigTree): Unit =
        throw ConfigWriteException(ConfigWriteFailure.Unsupported)
}
