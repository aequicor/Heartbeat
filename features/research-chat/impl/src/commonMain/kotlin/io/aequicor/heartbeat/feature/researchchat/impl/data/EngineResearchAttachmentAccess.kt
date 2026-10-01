package io.aequicor.heartbeat.feature.researchchat.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsEnabled
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchAttachmentAccess
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchAttachmentPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

@Inject
@ContributesBinding(ProfileScope::class)
internal class EngineResearchAttachmentAccess(private val facade: EngineFacade, private val toggles: FeatureToggles) :
    ResearchAttachmentAccess {
    override fun observe(target: EngineTarget): Flow<ResearchAttachmentPolicy> = combine(
        facade.models.observe(target.engine, target.binding),
        toggles.observe(AttachmentsEnabled),
    ) { catalog, enabled ->
        ResearchAttachmentPolicy(
            enabled,
            catalog.models.firstOrNull { it.target == target }?.inputSupport
                ?: PromptInputSupport.TextDocuments,
        )
    }
}
