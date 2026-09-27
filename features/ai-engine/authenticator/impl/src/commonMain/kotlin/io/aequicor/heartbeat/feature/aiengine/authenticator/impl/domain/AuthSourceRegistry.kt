package io.aequicor.heartbeat.feature.aiengine.authenticator.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthException
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceDraft
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * [AuthSources] over a metadata store and the profile vault. Mutations are serialized. A new managed value is written
 * before its metadata, a replacement after its new revision, and a value is removed before it is forgotten: metadata
 * never points at a value of another source and a replaced value never keeps the old revision.
 * [newToken] yields random `[a-z0-9]` tokens used for ids and managed-key revisions.
 */
class AuthSourceRegistry(
    private val store: AuthSourceStore,
    private val vault: ManagedKeyVault,
    scope: CoroutineScope,
    private val newToken: () -> String,
) : AuthSources {
    private val log = Log.tag("AuthSources")
    private val mutex = Mutex()

    override val state: StateFlow<List<AuthSource>> =
        store.observe().stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun get(id: AuthSourceId): AuthSource? = store.load().firstOrNull { it.info.id == id }

    override suspend fun addManagedKey(label: String, scope: AuthScope, key: Secret): AuthSource.ManagedKey =
        mutex.withLock {
            require(label.isNotBlank()) { "Blank source label" }
            val token = newToken()
            val source = AuthSource.ManagedKey(
                AuthSourceInfo(AuthSourceId("$SOURCE_PREFIX$token"), label, newRevision()),
                scope,
                AuthSecretId("$SECRET_PREFIX$token"),
            )
            log.i { "add managed key source=${source.info.id.value} provider=${scope.provider.value}" }
            vault.store(source.secret, key)
            try {
                store.save(store.load() + source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "metadata write failed, removing orphan value source=${source.info.id.value}" }
                vault.remove(source.secret)
                throw e
            }
            source
        }

    override suspend fun replaceManagedKey(id: AuthSourceId, key: Secret): AuthSource.ManagedKey = mutex.withLock {
        val sources = store.load()
        val current = sources.firstOrNull { it.info.id == id } as? AuthSource.ManagedKey ?: throw unavailable(id)
        val updated = current.copy(info = current.info.copy(revision = newRevision()))
        log.i { "replace managed key source=${id.value}" }
        // The new revision is saved first: if the value write fails, routes fail closed with SourceChanged
        // instead of silently using a different key under the old revision.
        store.save(sources.map { if (it.info.id == id) updated else it })
        vault.store(current.secret, key)
        updated
    }

    override suspend fun register(draft: AuthSourceDraft): AuthSource = mutex.withLock {
        require(draft.label.isNotBlank()) { "Blank source label" }
        val source = draft.toSource(AuthSourceId("$SOURCE_PREFIX${newToken()}"))
        log.i { "register ${source.kind} source=${source.info.id.value}" }
        store.save(store.load() + source)
        source
    }

    override suspend fun updateRevision(id: AuthSourceId, revision: AuthRevision): AuthSource = mutex.withLock {
        val sources = store.load()
        val current = sources.firstOrNull { it.info.id == id } ?: throw unavailable(id)
        require(current !is AuthSource.ManagedKey) { "Managed keys change revision only by replacement" }
        val updated = current.withRevision(revision)
        if (updated != current) {
            log.i { "source revision changed source=${id.value}" }
            store.save(sources.map { if (it.info.id == id) updated else it })
        }
        updated
    }

    override suspend fun forget(id: AuthSourceId): Unit = mutex.withLock {
        val sources = store.load()
        val current = sources.firstOrNull { it.info.id == id } ?: return@withLock
        log.i { "forget source=${id.value}" }
        if (current is AuthSource.ManagedKey) vault.remove(current.secret)
        store.save(sources - current)
    }

    private fun newRevision(): AuthRevision = AuthRevision.Known(newToken())

    private fun unavailable(id: AuthSourceId) = AuthException(AuthFailure(AuthFailureReason.SourceUnavailable, id))

    private companion object {
        const val SOURCE_PREFIX = "src_"
        const val SECRET_PREFIX = "aiengine_auth_"
    }
}

private val NoAuthRevision = AuthRevision.Known("none")

private fun AuthSourceDraft.toSource(id: AuthSourceId): AuthSource = when (this) {
    is AuthSourceDraft.ExternalKey -> AuthSource.ExternalKey(AuthSourceInfo(id, label, revision), scope, location)

    is AuthSourceDraft.CliLogin -> AuthSource.CliLogin(AuthSourceInfo(id, label, revision), scope, owner, location)

    is AuthSourceDraft.CredentialHelper ->
        AuthSource.CredentialHelper(AuthSourceInfo(id, label, AuthRevision.Unknown), scope, location)

    is AuthSourceDraft.NoAuth -> AuthSource.NoAuth(AuthSourceInfo(id, label, NoAuthRevision), scope)
}

private val AuthSource.kind: String
    get() = when (this) {
        is AuthSource.ManagedKey -> "managed-key"
        is AuthSource.ExternalKey -> "external-key"
        is AuthSource.CliLogin -> "cli-login"
        is AuthSource.CredentialHelper -> "credential-helper"
        is AuthSource.NoAuth -> "no-auth"
    }

private fun AuthSource.withRevision(revision: AuthRevision): AuthSource {
    val info = info.copy(revision = revision)
    return when (this) {
        is AuthSource.ManagedKey -> copy(info = info)
        is AuthSource.ExternalKey -> copy(info = info)
        is AuthSource.CliLogin -> copy(info = info)
        is AuthSource.CredentialHelper -> copy(info = info)
        is AuthSource.NoAuth -> this
    }
}
