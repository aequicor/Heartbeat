package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.profilefacade.ProfileSessions
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsEnabled
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineEnabled
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEntries
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatEnabled
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngineTools
import io.aequicor.heartbeat.feature.settings.api.UnifiedSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Entry points gated by feature toggles. Connection settings are profile routes, so they are offered only while
 * a profile is active; a guest studio has no tree that could show them.
 */
@Inject
@ContributesBinding(AiStudioScope::class)
internal class ToggleStudioEntries(toggles: FeatureToggles, sessions: ProfileSessions) : StudioEntries {
    override val showsResearch: Flow<Boolean> = combine(
        toggles.observe(ResearchChatEnabled),
        toggles.observe(StudioEngineRuntime),
        toggles.observe(KoogEngineEnabled),
        sessions.active,
    ) { research, runtime, koog, session -> research && runtime && koog && session != null }

    override val showsConnections: Flow<Boolean> =
        combine(toggles.observe(EngineConnectionsEnabled), sessions.active) { isEnabled, session ->
            isEnabled && session != null
        }

    override val showsUnifiedSettings: Flow<Boolean> = toggles.observe(UnifiedSettings)

    override val showsProfileSettings: Flow<Boolean> =
        combine(toggles.observe(SearchEngineTools), sessions.active) { isEnabled, session ->
            isEnabled && session != null
        }
}
