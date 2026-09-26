package io.aequicor.heartbeat.core.profilefacade.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ActiveProfileStorage
import io.aequicor.heartbeat.core.profilefacade.ProfileId

/**
 * Default binding with the lowest priority: keeps the id in memory only, so a profile is NOT restored
 * after process death. A persistent implementation (DataStore) overrides it with a higher priority.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, priority = Int.MIN_VALUE)
@Inject
internal class InMemoryActiveProfileStorage : ActiveProfileStorage {

    private val log = Log.tag("ActiveProfileStorage")
    private var id: ProfileId? = null

    init {
        log.w { "active profile is kept in memory only: bind a persistent ActiveProfileStorage" }
    }

    override suspend fun read(): ProfileId? {
        log.d { "read active profile: ${if (id == null) "none" else "present"}" }
        return id
    }

    override suspend fun write(id: ProfileId?) {
        log.d { "write active profile: ${if (id == null) "cleared" else "set"}" }
        this.id = id
    }
}
