package io.aequicor.heartbeat.core.secrets.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.secrets.SecretStorageInfo
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.core.secrets.impl.data.ProfileSecretRepository
import io.aequicor.heartbeat.core.secrets.impl.data.ProtectedVault
import io.aequicor.heartbeat.core.secrets.impl.data.VaultRegistry
import io.aequicor.heartbeat.core.secrets.impl.domain.ProfileSecretStore

@SingleIn(AppScope::class)
@Inject
internal class SecretsRuntime(backend: ProtectedVault, dispatchers: DispatcherProvider) {
    val registry = VaultRegistry(backend, dispatchers)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class SecretsMetadata(backend: ProtectedVault) : SecretStorageInfo {
    override val protection = backend.protection
}

@ContributesBinding(ProfileScope::class)
@SingleIn(ProfileScope::class)
@Inject
internal class SecretsBinding(
    runtime: SecretsRuntime,
    profile: ProfileId,
    @ForScope(ProfileScope::class) scope: ScopeHandle,
    @ForScope(ProfileScope::class) stores: DataStores,
) : SecretStore by createStore(runtime, profile, scope, stores)

private fun createStore(
    runtime: SecretsRuntime,
    profile: ProfileId,
    scope: ScopeHandle,
    stores: DataStores,
): SecretStore {
    // Attaching DataStores shares its lifecycle gate: reopening cannot bypass an ongoing profile wipe.
    check(stores.owner == StorageOwner.Profile(profile)) { "Mismatched profile storage owner" }
    return ProfileSecretStore(
        ProfileSecretRepository(runtime.registry, profile) { check(!scope.isClosed) { "Profile is closed" } },
    )
}
