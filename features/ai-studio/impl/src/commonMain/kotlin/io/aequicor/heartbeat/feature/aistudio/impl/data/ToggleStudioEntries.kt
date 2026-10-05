package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.profilefacade.ProfileSessions
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningEnabled
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsEnabled
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineEnabled
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEntries
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsEnabled
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiEnabled
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatEnabled
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngineTools
import io.aequicor.heartbeat.feature.settings.api.UnifiedSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Entry points gated by feature toggles. Connection settings are profile routes, so they are offered only while
 * a profile is active; a guest studio has no tree that could show them.
 */
@Inject
@ContributesBinding(AiStudioScope::class)
@OptIn(ExperimentalCoroutinesApi::class)
internal class ToggleStudioEntries(
    toggles: FeatureToggles,
    sessions: ProfileSessions,
    questions: Lazy<StudioQuestionBridge>,
    checklists: Lazy<StudioChecklists>,
) : StudioEntries {
    override val showsAttachments: Flow<Boolean> = toggles.observe(AttachmentsEnabled)

    override val showsResearch: Flow<Boolean> = combine(
        toggles.observe(ResearchChatEnabled),
        toggles.observe(StudioEngineRuntime),
        toggles.observe(KoogEngineEnabled),
        sessions.active,
    ) { research, runtime, koog, session -> research && runtime && koog && session != null }

    // The profile bridge runs from the profile start; a guest studio has no profile questions.
    override val questionSources: Flow<Set<String>> = sessions.active.flatMapLatest { session ->
        if (session == null) flowOf(emptySet()) else questions.value.sources
    }

    override val checklistIds: Flow<Set<String>> = sessions.active.flatMapLatest { session ->
        if (session == null) {
            flowOf(
                emptySet(),
            )
        } else {
            checklists.value.events.map { cards -> cards.map { it.id }.toSet() }
        }
    }

    override val showsConnections: Flow<Boolean> =
        combine(toggles.observe(EngineConnectionsEnabled), sessions.active) { isEnabled, session ->
            isEnabled && session != null
        }

    override val showsUnifiedSettings: Flow<Boolean> = toggles.observe(UnifiedSettings)

    // The demo runtime has no engine tools, so the command would reach no remember tool.
    override val showsRemember: Flow<Boolean> = combine(
        toggles.observe(AgentLearningEnabled),
        toggles.observe(StudioEngineRuntime),
        sessions.active,
    ) { isEnabled, runtime, session -> isEnabled && runtime && session != null }

    // Organisms live in the profile and grow through engine sessions, which the demo runtime does not have.
    override val showsOrganism: Flow<Boolean> = combine(
        toggles.observe(OrganicAiEnabled),
        toggles.observe(StudioEngineRuntime),
        sessions.active,
    ) { isEnabled, runtime, session -> isEnabled && runtime && session != null }

    override val showsProfileSettings: Flow<Boolean> =
        combine(toggles.observe(SearchEngineTools), sessions.active) { isEnabled, session ->
            isEnabled && session != null
        }
}
