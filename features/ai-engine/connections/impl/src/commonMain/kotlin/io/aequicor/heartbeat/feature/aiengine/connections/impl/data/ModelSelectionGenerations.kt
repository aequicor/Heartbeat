package io.aequicor.heartbeat.feature.aiengine.connections.impl.data

import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.api.SelectedModelGeneration
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

internal val ModelSelectionGenerationsKey: StoreKey<ModelSelectionGenerations> =
    jsonKey("model_check_generations", serializer<ModelSelectionGenerations>())

/**
 * A sidecar keeps the legacy choice JSON readable after rollback. Pending metadata precedes the choice write;
 * committed metadata follows it. A failed or cancelled write may have applied, so readers handle either projection.
 * New generations become usable only after commit; unchanged generations survive a partial save of another model.
 * A differing legacy-writer projection invalidates metadata; a legacy writer's identical final projection cannot
 * reveal an intervening disable/re-enable. These tokens never establish authentication or route availability.
 */
@Serializable
internal data class ModelSelectionGenerations(
    val before: ModelSelection,
    val after: ModelSelection,
    val previous: List<SelectedModelGeneration>,
    val next: List<SelectedModelGeneration>,
    val isCommitted: Boolean = false,
) {
    fun attach(selection: ModelSelection): ModelSelection {
        val choices = selection.copy(generations = emptyList())
        val generations = when {
            isCommitted && choices == after -> next
            isCommitted -> emptyList()
            choices == before -> previous
            choices == after -> unchanged()
            else -> emptyList()
        }
        return choices.copy(generations = generations)
    }

    private fun unchanged(): List<SelectedModelGeneration> {
        val prior = before.copy(generations = previous)
        return next.filter { prior.checkGeneration(it.binding, it.model) == it.value }
    }

    override fun toString(): String = "ModelSelectionGenerations(committed=$isCommitted)"
}
