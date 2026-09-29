package io.aequicor.heartbeat.feature.aiengine.authenticator.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretRemoval
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.core.secrets.SecretUsage
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthException
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.spi.AuthCredentials
import io.aequicor.heartbeat.feature.aiengine.authenticator.impl.domain.AuthSourceStore
import io.aequicor.heartbeat.feature.aiengine.authenticator.impl.domain.ManagedKeyVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlin.coroutines.cancellation.CancellationException

/** Profile key-value store of source metadata; labels may contain PII, so values are not logged. */
internal val AuthSourcesSpec = KeyValueSpec("aiengine_auth_sources")
private val SourcesKey = jsonKey("sources", ListSerializer(AuthSource.serializer()))

/** [AuthSourceStore] in the profile key-value store. */
class AuthSourceStorage(private val store: KeyValueStore) : AuthSourceStore {
    private val log = Log.tag("AuthSourceStorage")

    override fun observe(): Flow<List<AuthSource>> {
        log.d { "observe sources" }
        return store.observe(SourcesKey).map { it.orEmpty() }
    }

    override suspend fun load(): List<AuthSource> {
        log.d { "load sources" }
        return store.get(SourcesKey).orEmpty()
    }

    override suspend fun save(sources: List<AuthSource>) {
        log.d { "save sources count=${sources.size}" }
        store.set(SourcesKey, sources)
    }
}

/**
 * [ManagedKeyVault] and [AuthCredentials] over the profile [SecretStore]. Each managed key has one value and one
 * usage slot, so the vault refuses removal while another consumer still references it: [remove] then throws
 * [ManagedKeyInUseException]. A failed [store] of a new value removes that value again.
 */
class SecretStoreKeyVault(private val secrets: SecretStore) :
    ManagedKeyVault,
    AuthCredentials {
    private val log = Log.tag("SecretStoreKeyVault")

    override suspend fun store(slot: AuthSecretId, key: Secret) {
        log.d { "store managed key id=${slot.value}" }
        val hasExisted = slot.key() in secrets.keys()
        secrets.write(slot.key(), key)
        try {
            secrets.bind(slot.usage(), slot.key())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "bind failed id=${slot.value}" }
            if (!hasExisted) rollbackWrite(slot, e)
            throw e
        }
    }

    private suspend fun rollbackWrite(slot: AuthSecretId, cause: Exception) {
        try {
            secrets.remove(slot.key())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "rollback of unbound managed key failed id=${slot.value}" }
            cause.addSuppressed(e)
        }
    }

    override suspend fun read(slot: AuthSecretId): Secret? {
        log.d { "read managed key id=${slot.value}" }
        return secrets.readFor(slot.usage())
    }

    override suspend fun contains(slot: AuthSecretId): Boolean {
        log.d { "probe managed key id=${slot.value}" }
        return slot.key() in secrets.keys()
    }

    override suspend fun remove(slot: AuthSecretId) {
        log.d { "remove managed key id=${slot.value}" }
        if (slot.key() !in secrets.keys()) return
        secrets.bind(slot.usage(), null)
        when (val removal = secrets.remove(slot.key())) {
            SecretRemoval.Removed, SecretRemoval.Missing -> Unit

            is SecretRemoval.InUse -> {
                log.w { "managed key kept, still referenced usages=${removal.usages.size}" }
                // The source is kept, so it keeps its own reference to the value.
                restoreUsage(slot)
                throw ManagedKeyInUseException(slot)
            }
        }
    }

    private suspend fun restoreUsage(slot: AuthSecretId) {
        try {
            secrets.bind(slot.usage(), slot.key())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "managed key usage not restored id=${slot.value}" }
        }
    }

    override suspend fun managedKey(source: AuthSource.ManagedKey): Secret =
        read(source.secret) ?: throw AuthException(AuthFailure(AuthFailureReason.NotAuthenticated, source.info.id))

    private fun AuthSecretId.key() = SecretKey(value)

    private fun AuthSecretId.usage() = SecretUsage(USAGE_TYPE, value, USAGE_SLOT)

    private companion object {
        const val USAGE_TYPE = "aiengine-auth"
        const val USAGE_SLOT = "key"
    }
}

/** A managed key is still referenced by another consumer, so its value was kept. */
class ManagedKeyInUseException(slot: AuthSecretId) :
    IllegalStateException("Managed key is still in use id=${slot.value}")
