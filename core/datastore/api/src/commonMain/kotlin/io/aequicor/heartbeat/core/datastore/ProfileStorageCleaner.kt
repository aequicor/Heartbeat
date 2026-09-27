package io.aequicor.heartbeat.core.datastore

import io.aequicor.heartbeat.core.profilefacade.ProfileId

/** App-scoped participant in explicit profile removal, invoked before deleting regular storage files. */
public fun interface ProfileStorageCleaner {
    /** Idempotently removes external protected data. Failure stops the wipe; callers can retry. */
    public suspend fun wipeProfile(id: ProfileId)
}
