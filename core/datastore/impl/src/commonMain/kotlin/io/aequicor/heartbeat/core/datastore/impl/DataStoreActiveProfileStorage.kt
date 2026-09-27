package io.aequicor.heartbeat.core.datastore.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ActiveProfileStorage
import io.aequicor.heartbeat.core.profilefacade.ProfileId

/** Persistent active profile: overrides the in-memory default of core:profile-facade:impl. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, priority = 0)
@Inject
internal class DataStoreActiveProfileStorage(
    @ForScope(AppScope::class) private val stores: DataStores,
) : ActiveProfileStorage {

    private val log = Log.tag("ActiveProfileStorage")
    private val store by lazy { stores.keyValue(SPEC) }

    override suspend fun read(): ProfileId? {
        val id = store.get(ACTIVE_PROFILE)?.let(::ProfileId)
        log.d { "read active profile: ${if (id == null) "none" else "present"}" }
        return id
    }

    override suspend fun write(id: ProfileId?) {
        log.d { "write active profile: ${if (id == null) "cleared" else "set"}" }
        if (id == null) store.remove(ACTIVE_PROFILE) else store.set(ACTIVE_PROFILE, id.value)
    }

    private companion object {
        val SPEC = KeyValueSpec("core_profile") // core_* names are reserved for the core
        val ACTIVE_PROFILE = stringKey("active_profile_id")
    }
}
