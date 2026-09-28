package io.aequicor.heartbeat.feature.aiengine.koog.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnection
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnections
import io.aequicor.heartbeat.feature.aiengine.koog.api.koogProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val ConnectionsSpec = KeyValueSpec("ai_koog_connections")

/** A connection this version cannot decode is kept on disk and hidden instead of erasing the others. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class StoredKoogConnections(
    @ForScope(ProfileScope::class) stores: DataStores,
) : KoogConnections {
    private val connections =
        StoredJsonList(stores.keyValue(ConnectionsSpec), "connections", KoogConnection.serializer())
    private val mutex = Mutex()

    override suspend fun list(): List<KoogConnection> = connections.items()

    override suspend fun put(connection: KoogConnection) {
        requireNotNull(koogProvider(connection.source)) { "Unsupported Koog authentication source" }
        mutex.withLock { connections.update { current -> current.replace(connection) } }
    }

    override suspend fun remove(binding: EngineBindingId) {
        mutex.withLock { connections.update { current -> current.filterNot { it.binding.id == binding } } }
    }
}

private fun List<KoogConnection>.replace(connection: KoogConnection): List<KoogConnection> {
    val previous = firstOrNull { it.source.info.id == connection.source.info.id }?.source
    require(
        previous == null || previous == connection.source ||
            previous.info.revision != connection.source.info.revision,
    ) { "Changed source metadata requires a new revision" }
    val others = filterNot { it.binding.id == connection.binding.id }.map {
        if (it.source.info.id == connection.source.info.id) it.copy(source = connection.source) else it
    }
    return others + connection
}
