package io.aequicor.heartbeat.feature.settings.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.profilefacade.ProfileSessions
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningEnabled
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsEnabled
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngineTools
import io.aequicor.heartbeat.feature.settings.api.SettingsSection
import io.aequicor.heartbeat.feature.settings.impl.di.scope.SettingsScope
import io.aequicor.heartbeat.feature.settings.impl.domain.SettingsSections
import io.aequicor.heartbeat.feature.settings.impl.domain.availableSections
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

/**
 * Sections gated like their former entry points: models and search are profile routes behind their own toggles
 * and need an active profile (the guest tree cannot show them); feature flags are always available, as the flag
 * panel was.
 */
@Inject
@ContributesBinding(SettingsScope::class)
internal class ToggleSettingsSections(toggles: FeatureToggles, sessions: ProfileSessions) : SettingsSections {
    override val available: Flow<ImmutableList<SettingsSection>> = combine(
        toggles.observe(EngineConnectionsEnabled),
        toggles.observe(SearchEngineTools),
        toggles.observe(ComputerUseEnabled),
        // Profile features beyond the five-flow overload are combined first.
        combine(toggles.observe(AgentLearningEnabled), toggles.observe(HarnessEnabled)) { learning, harness ->
            learning to harness
        },
        sessions.active,
    ) { connections, search, computerUse, (learning, harness), session ->
        availableSections(
            isModelsEnabled = connections,
            isSearchEnabled = search,
            hasProfile = session != null,
            isComputerUseEnabled = computerUse,
            isLearningEnabled = learning,
            isHarnessEnabled = harness,
        )
    }
        // Sections without a toggle appear at once; gated ones join when their toggles are read from storage.
        .onStart { emit(availableSections(isModelsEnabled = false, isSearchEnabled = false, hasProfile = false)) }
        .distinctUntilChanged()
}
