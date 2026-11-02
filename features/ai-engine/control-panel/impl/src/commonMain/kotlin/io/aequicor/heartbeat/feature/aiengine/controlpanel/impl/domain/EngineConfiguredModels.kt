package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.domain

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Observes saved usable model choices without probing a provider or changing the user's selection. */
internal fun ModelSelections.configuredEngines(
    bindings: Flow<List<EngineBinding>>,
    sources: Flow<Set<AuthSourceId>>,
): Flow<Set<EngineId>> = combine(observe(), bindings, sources) { selected, saved, available ->
    saved.filter {
        it.isEnabled && it.authSource in available && selected.enabled(it.id).isNotEmpty()
    }.map { it.engine }.toSet()
}.distinctUntilChanged()
