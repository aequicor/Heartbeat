package io.aequicor.heartbeat.core.secrets.impl

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretRemoval
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.core.secrets.SecretUsage

@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class ProfileSecretStore(
    private val registry: VaultRegistry,
    private val profile: ProfileId,
    @ForScope(ProfileScope::class) private val scope: ScopeHandle,
    @ForScope(ProfileScope::class) stores: DataStores,
) : SecretStore {
    init {
        // Attaching DataStores shares its lifecycle gate: reopening cannot bypass an ongoing profile wipe.
        check(stores.owner == StorageOwner.Profile(profile)) { "Mismatched profile storage owner" }
    }

    override suspend fun write(key: SecretKey, value: Secret) {
        access("write", changed = true) { state ->
            val previous = state.values.put(key.value, value.reveal { it.copyOf() })
            previous?.fill('\u0000')
        }
    }

    override suspend fun read(key: SecretKey): Secret? =
        access("read") { state -> state.values[key.value]?.let(::Secret) }

    override suspend fun keys(): List<SecretKey> = access("keys") { state -> state.values.keys.map(::SecretKey) }

    override suspend fun bind(usage: SecretUsage, key: SecretKey?) {
        access("bind", changed = true) { state ->
            require(key == null || key.value in state.values) { "Missing secret" }
            state.references.removeAll { it.usage() == usage }
            if (key != null) state.references.add(VaultReference(usage.type, usage.id, usage.slot, key.value))
        }
    }

    override suspend fun readFor(usage: SecretUsage): Secret? = access("resolve") { state ->
        val reference = state.references.find { it.usage() == usage }
        val chars = reference?.let { state.values[it.key] }
        chars?.let(::Secret)
    }

    override suspend fun usages(key: SecretKey): List<SecretUsage> =
        access("usages") { state -> state.references.filter { it.key == key.value }.map { it.usage() } }

    override suspend fun remove(key: SecretKey): SecretRemoval = access("remove", changed = true) { state ->
        val usages = state.references.filter { it.key == key.value }.map { it.usage() }
        when {
            usages.isNotEmpty() -> SecretRemoval.InUse(usages)
            state.values.remove(key.value)?.also { it.fill('\u0000') } != null -> SecretRemoval.Removed
            else -> SecretRemoval.Missing
        }
    }

    private suspend fun <T> access(operation: String, changed: Boolean = false, action: (VaultState) -> T): T =
        registry.access(profile, operation, changed, { check(!scope.isClosed) { "Profile is closed" } }, action)
}
