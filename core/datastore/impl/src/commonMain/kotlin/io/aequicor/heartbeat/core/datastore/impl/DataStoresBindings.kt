package io.aequicor.heartbeat.core.datastore.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.StorageMaintenance
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.ProfileSessions

internal typealias AppOwnedStores =
    @ForScope(AppScope::class)
    DataStores
internal typealias ProfileOwnedStores =
    @ForScope(ProfileScope::class)
    DataStores

/** `@ForScope(AppScope::class) DataStores`: storages that live forever. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<AppOwnedStores>())
@Inject
internal class AppDataStores(
    registry: StoreRegistry,
    @ForScope(AppScope::class) scope: ScopeHandle,
) : DataStores by registry.attach(StorageOwner.App, scope)

/** `@ForScope(ProfileScope::class) DataStores`: storages of the active profile, closed with it. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<ProfileOwnedStores>())
@Inject
internal class ProfileDataStores(
    registry: StoreRegistry,
    id: ProfileId,
    @ForScope(ProfileScope::class) scope: ScopeHandle,
) : DataStores by registry.attach(StorageOwner.Profile(id), scope)

@ContributesBinding(AppScope::class)
@Inject
internal class StorageMaintenanceImpl(private val registry: StoreRegistry, private val sessions: ProfileSessions) :
    StorageMaintenance {
    override suspend fun awaitClosed() = registry.awaitClosed()

    override suspend fun wipeProfile(id: ProfileId) {
        check(sessions.active.value?.id != id) { "profile ${id.value} is active: close the session before wiping it" }
        registry.wipeProfile(id)
    }
}
