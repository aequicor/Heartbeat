package io.aequicor.heartbeat.core.secrets.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.ProfileStorageCleaner
import io.aequicor.heartbeat.core.profilefacade.ProfileId

@ContributesIntoSet(AppScope::class)
@Inject
internal class SecretsProfileCleaner(private val registry: VaultRegistry) : ProfileStorageCleaner {
    override suspend fun wipeProfile(id: ProfileId) {
        registry.wipe(id)
    }
}
