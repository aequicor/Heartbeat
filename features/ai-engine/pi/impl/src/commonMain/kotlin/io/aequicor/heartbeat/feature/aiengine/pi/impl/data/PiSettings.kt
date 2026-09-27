package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

@Serializable
internal data class PiConfiguration(
    val bindings: Map<String, AuthSource.ManagedKey> = emptyMap(),
    val workspaces: Map<String, String> = emptyMap(),
)

@Inject
@SingleIn(ProfileScope::class)
internal class PiSettings(
    @ForScope(ProfileScope::class) stores: DataStores,
) {
    private val store = stores.keyValue(KeyValueSpec("pi_engine"))
    private val key = jsonKey("configuration", serializer<PiConfiguration>())
    private val mutex = Mutex()

    suspend fun snapshot(): PiConfiguration = store.get(key) ?: PiConfiguration()

    /** Routes [binding] to [source]; other bindings of the same source id receive its new revision. */
    suspend fun bind(binding: EngineBindingId, source: AuthSource.ManagedKey) = mutex.withLock {
        val current = snapshot()
        val refreshed = current.bindings.mapValues { (_, value) ->
            if (value.info.id == source.info.id) source else value
        }
        store.set(key, current.copy(bindings = refreshed + (binding.value to source)))
    }

    /** Removes the route of [binding]; returns the removed source, if any. */
    suspend fun unbind(binding: EngineBindingId): AuthSource.ManagedKey? = mutex.withLock {
        val current = snapshot()
        val removed = current.bindings[binding.value] ?: return@withLock null
        store.set(key, current.copy(bindings = current.bindings - binding.value))
        removed
    }

    suspend fun workspace(ref: WorkspaceRef, directory: String) = mutex.withLock {
        val current = snapshot()
        store.set(key, current.copy(workspaces = current.workspaces + (ref.value to directory)))
    }

    suspend fun source(id: AuthSourceId): AuthSource.ManagedKey =
        snapshot().bindings.values.firstOrNull { it.info.id == id }
            ?: piFailure(EngineFailure.Authentication(AuthFailure(AuthFailureReason.SourceUnavailable, id)))
}
