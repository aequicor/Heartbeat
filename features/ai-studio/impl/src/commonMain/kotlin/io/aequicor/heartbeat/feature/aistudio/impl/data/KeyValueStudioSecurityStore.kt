package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueReadPolicy
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityLevel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSecurityStore

private val SecuritySpec = KeyValueSpec("ai_studio_security", readPolicy = KeyValueReadPolicy.Strict)
private val LevelKey = stringKey("confirmation_level")

/** One permanent profile setting; no chat defaults or engine acknowledgements can overwrite it. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class KeyValueStudioSecurityStore(
    @ForScope(ProfileScope::class) stores: DataStores,
) : StudioSecurityStore {
    private val store = stores.keyValue(SecuritySpec)

    override suspend fun load(): StudioSecurityLevel? {
        val saved = store.get(LevelKey) ?: return null
        // A malformed or newer value is a load failure, never an absent preference that enables Automatic.
        return StudioSecurityLevel.entries.firstOrNull { it.name == saved }
            ?: error("Unknown saved studio security level")
    }

    override suspend fun save(level: StudioSecurityLevel) = store.set(LevelKey, level.name)
}
