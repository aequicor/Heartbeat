package io.aequicor.heartbeat.feature.aiengine.koog.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnection
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnections
import io.aequicor.heartbeat.feature.aiengine.koog.api.koogProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

private val ConnectionsSpec = KeyValueSpec("ai_koog_connections")
private val ConnectionsKey = jsonKey("connections", ListSerializer(KoogConnection.serializer()))

@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class StoredKoogConnections(
    @ForScope(ProfileScope::class) stores: DataStores,
) : KoogConnections {
    private val store = stores.keyValue(ConnectionsSpec)
    private val mutex = Mutex()

    override suspend fun list(): List<KoogConnection> = store.get(ConnectionsKey).orEmpty()

    override suspend fun put(connection: KoogConnection) {
        requireNotNull(koogProvider(connection.source)) { "Unsupported Koog authentication source" }
        mutex.withLock {
            val current = list()
            val previous = current.firstOrNull { it.source.info.id == connection.source.info.id }?.source
            require(
                previous == null || previous == connection.source ||
                    previous.info.revision != connection.source.info.revision,
            ) { "Changed source metadata requires a new revision" }
            val others = current.filterNot { it.binding.id == connection.binding.id }.map {
                if (it.source.info.id == connection.source.info.id) it.copy(source = connection.source) else it
            }
            store.set(ConnectionsKey, others + connection)
        }
    }

    override suspend fun remove(binding: EngineBindingId) {
        mutex.withLock { store.set(ConnectionsKey, list().filterNot { it.binding.id == binding }) }
    }
}
