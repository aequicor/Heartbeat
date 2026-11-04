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
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.connections.api.SelectedModelGeneration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

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

    override fun observe(): Flow<ModelSelection> = combine(
        store.observe(ModelSelectionKey),
        store.observe(ModelSelectionGenerationsKey),
    ) { _, _ -> Unit }.map {
        // Separate per-key flows can briefly combine different snapshots. Read under the writer's mutex instead.
        mutex.withLock { readCurrent() }
    }.distinctUntilChanged()

    override suspend fun update(change: (ModelSelection) -> ModelSelection): ModelSelection = mutex.withLock {
        val choices = store.get(ModelSelectionKey) ?: ModelSelection()
        val metadata = store.get(ModelSelectionGenerationsKey)
        val before = metadata?.attach(choices) ?: choices
        val changed = change(before)
        val generations = changed.bindings.flatMap { binding ->
            binding.models.map { model ->
                val generation = before.checkGeneration(binding.binding, model) ?: Uuid.random().toString()
                SelectedModelGeneration(binding.binding, model, generation)
            }
        }
        val updated = changed.copy(generations = generations)
        if (before == updated && metadata?.isCommitted == true) return@withLock before
        val pending = ModelSelectionGenerations(
            before = choices.copy(generations = emptyList()),
            after = changed.copy(generations = emptyList()),
            previous = before.generations,
            next = generations,
        )
        store.set(ModelSelectionGenerationsKey, pending)
        if (pending.before != pending.after) store.set(ModelSelectionKey, changed)
        log.d { "model selection saved bindings=${updated.bindings.size} default=${updated.defaultTarget != null}" }
        finishGenerations(pending, updated)
    }

    private suspend fun readCurrent(): ModelSelection {
        val choices = store.get(ModelSelectionKey) ?: ModelSelection()
        return store.get(ModelSelectionGenerationsKey)?.attach(choices) ?: choices
    }

    private suspend fun finishGenerations(
        pending: ModelSelectionGenerations,
        updated: ModelSelection,
    ): ModelSelection = try {
        store.set(
            ModelSelectionGenerationsKey,
            pending.copy(before = pending.after, previous = pending.next, isCommitted = true),
        )
        updated
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error.redactedFailure()) { "Model choices saved but check generations could not be confirmed" }
        pending.attach(updated)
    }
}
