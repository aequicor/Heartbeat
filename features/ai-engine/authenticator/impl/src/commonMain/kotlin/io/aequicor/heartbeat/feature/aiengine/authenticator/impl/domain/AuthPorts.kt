package io.aequicor.heartbeat.feature.aiengine.authenticator.impl.domain

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import kotlinx.coroutines.flow.Flow

/** Persistent non-secret source metadata of one profile. Failures propagate. */
interface AuthSourceStore {
    /** Current list and its changes. */
    fun observe(): Flow<List<AuthSource>>

    /** Current list. */
    suspend fun load(): List<AuthSource>

    /** Replaces the whole list atomically. */
    suspend fun save(sources: List<AuthSource>)
}

/** Profile vault slots of Heartbeat-owned keys. Values are never logged or cached. */
interface ManagedKeyVault {
    /** Creates or replaces the value; the caller keeps ownership of [key]. */
    suspend fun store(slot: AuthSecretId, key: Secret)

    /** A newly owned value, or null; the caller closes it. */
    suspend fun read(slot: AuthSecretId): Secret?

    /** Whether a value exists, without revealing it. */
    suspend fun contains(slot: AuthSecretId): Boolean

    /** Removes the value; missing values are ignored. */
    suspend fun remove(slot: AuthSecretId)
}
