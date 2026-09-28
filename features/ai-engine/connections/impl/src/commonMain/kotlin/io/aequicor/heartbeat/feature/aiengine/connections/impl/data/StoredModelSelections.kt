package io.aequicor.heartbeat.feature.aiengine.connections.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal val ModelSelectionSpec = KeyValueSpec("ai_engine_connections")
internal val ModelSelectionKey = jsonKey("model_selection", ModelSelection.serializer())

/** Profile key-value storage of the model choice; updates are serialized so concurrent edits are not lost. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class StoredModelSelections(
    @ForScope(ProfileScope::class) stores: DataStores,
) : ModelSelections {
    private val log = Log.tag("StoredModelSelections")
    private val store = stores.keyValue(ModelSelectionSpec)
    private val mutex = Mutex()

    override fun observe(): Flow<ModelSelection> = store.observe(ModelSelectionKey).map { it ?: ModelSelection() }

    override suspend fun update(change: (ModelSelection) -> ModelSelection): ModelSelection = mutex.withLock {
        val updated = change(store.get(ModelSelectionKey) ?: ModelSelection())
        store.set(ModelSelectionKey, updated)
        log.d { "model selection saved bindings=${updated.bindings.size} default=${updated.defaultTarget != null}" }
        updated
    }
}
