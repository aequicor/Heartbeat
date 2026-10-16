package io.aequicor.heartbeat.feature.adaptivesubagents.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.Directory
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.DirectoryConnection
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.DirectoryModel
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.ModelDirectory
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Observes enabled models and connections for the settings screens, from cached catalogs only. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class FacadeModelDirectory(
    private val facade: Lazy<EngineFacade>,
    private val selections: Lazy<ModelSelections>,
    private val sources: Lazy<AuthSources>,
) : ModelDirectory {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(): Flow<Directory> {
        val facade = facade.value
        return combine(
            selections.value.observe(),
            facade.bindings.state,
            facade.engines.state,
            sources.value.state,
        ) { selected, bindings, engines, auth ->
            selected.bindings.mapNotNull { enabled ->
                val binding =
                    bindings.firstOrNull { it.id == enabled.binding && it.isEnabled } ?: return@mapNotNull null
                val engine = engines.firstOrNull { it.descriptor.id == binding.engine }?.descriptor
                    ?: return@mapNotNull null
                val label = auth.firstOrNull { it.info.id == binding.authSource }?.info?.label.orEmpty()
                val connection = listOf(engine.title, label).filter(String::isNotBlank).joinToString(" · ")
                facade.models.observe(binding.engine, binding.id).map { snapshot ->
                    DirectoryConnection(binding.id, connection) to enabled.models.sortedBy { it.value }.map { model ->
                        val target = EngineTarget(binding.engine, binding.id, model)
                        val info = snapshot.models.firstOrNull { it.target == target }
                        DirectoryModel(
                            target,
                            info?.title?.takeIf(String::isNotBlank) ?: model.value,
                            engine.title,
                            connection,
                            info?.reasoningEfforts.orEmpty(),
                            info?.defaultReasoningEffort,
                        )
                    }
                }
            }
        }.flatMapLatest { parts ->
            if (parts.isEmpty()) {
                flowOf(Directory())
            } else {
                combine(parts) { all -> Directory(all.flatMap { it.second }, all.map { it.first }) }
            }
        }
    }
}
