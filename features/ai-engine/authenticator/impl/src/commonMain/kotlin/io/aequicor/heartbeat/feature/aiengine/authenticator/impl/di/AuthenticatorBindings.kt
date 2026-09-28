package io.aequicor.heartbeat.feature.aiengine.authenticator.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthChecks
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.spi.AuthCredentials
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.spi.Authenticator
import io.aequicor.heartbeat.feature.aiengine.authenticator.impl.data.AuthSourceStorage
import io.aequicor.heartbeat.feature.aiengine.authenticator.impl.data.AuthSourcesSpec
import io.aequicor.heartbeat.feature.aiengine.authenticator.impl.data.SecretStoreKeyVault
import io.aequicor.heartbeat.feature.aiengine.authenticator.impl.domain.AuthCheckRunner
import io.aequicor.heartbeat.feature.aiengine.authenticator.impl.domain.AuthSourceRegistry
import io.aequicor.heartbeat.feature.aiengine.authenticator.impl.domain.ManagedKeyAuthenticator
import io.aequicor.heartbeat.feature.aiengine.authenticator.impl.domain.NoAuthAuthenticator
import kotlin.time.Clock
import kotlin.uuid.Uuid

// Binding containers are public: the graph that includes them is generated in :platform-main:di-bundle.

/** Profile-scoped authentication services; engine adapters add their authenticators to the same set. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object AuthenticatorBindings {
    /** One vault adapter per profile serves both the registry and trusted adapters. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun vault(secrets: SecretStore): SecretStoreKeyVault = SecretStoreKeyVault(secrets)

    /** Credential access for engine adapters. */
    @Provides
    fun credentials(vault: SecretStoreKeyVault): AuthCredentials = vault

    /** Source registry of the profile. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun sources(
        @ForScope(ProfileScope::class) stores: DataStores,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        vault: SecretStoreKeyVault,
    ): AuthSources = AuthSourceRegistry(
        AuthSourceStorage(stores.keyValue(AuthSourcesSpec)),
        vault,
        scope.coroutineScope,
    ) { Uuid.random().toHexString().take(TOKEN_LENGTH) }

    /** Verification over every contributed authenticator. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun checks(sources: AuthSources, authenticators: Set<Authenticator>, clock: Clock): AuthChecks =
        AuthCheckRunner(sources, authenticators, setOf(ManagedKeyAuthenticator.Id, NoAuthAuthenticator.Id), clock)

    /** Shared local check of Heartbeat-owned keys. */
    @Provides
    @IntoSet
    fun managedKeyAuthenticator(vault: SecretStoreKeyVault, clock: Clock): Authenticator =
        ManagedKeyAuthenticator(vault, clock)

    /** Shared check of credential-free endpoints. */
    @Provides
    @IntoSet
    fun noAuthAuthenticator(clock: Clock): Authenticator = NoAuthAuthenticator(clock)

    private const val TOKEN_LENGTH = 20
}
