package io.aequicor.heartbeat.feature.researchchat.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal data class ResearchAttachmentPolicy(val isEnabled: Boolean, val support: PromptInputSupport)

/** Cached capabilities of the exact research connection/model, without starting native discovery. */
internal interface ResearchAttachmentAccess {
    fun observe(target: EngineTarget): Flow<ResearchAttachmentPolicy>

    companion object {
        val Disabled: ResearchAttachmentAccess = object : ResearchAttachmentAccess {
            override fun observe(target: EngineTarget): Flow<ResearchAttachmentPolicy> =
                flowOf(ResearchAttachmentPolicy(false, PromptInputSupport.TextDocuments))
        }
    }
}
