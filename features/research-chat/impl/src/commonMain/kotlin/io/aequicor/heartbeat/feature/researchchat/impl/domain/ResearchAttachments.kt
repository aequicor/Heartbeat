package io.aequicor.heartbeat.feature.researchchat.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.attachments.api.AttachmentDescriptor
import io.aequicor.heartbeat.feature.attachments.api.AttachmentId
import io.aequicor.heartbeat.feature.attachments.api.AttachmentInput
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResource

/** Moves existing local source bytes through the same profile machine as newly picked files. */
internal interface ResearchAttachments {
    suspend fun persist(source: ResearchResource, support: PromptInputSupport, migration: Boolean): ResearchResource
    suspend fun import(inputs: List<AttachmentInput>, support: PromptInputSupport): List<AttachmentDescriptor>
    suspend fun registered(id: AttachmentId): AttachmentDescriptor

    companion object {
        /** Legacy storage fixtures do not own a profile attachment machine. */
        val Legacy: ResearchAttachments = object : ResearchAttachments {
            override suspend fun persist(source: ResearchResource, support: PromptInputSupport, migration: Boolean) =
                source
            override suspend fun import(
                inputs: List<AttachmentInput>,
                support: PromptInputSupport,
            ): List<AttachmentDescriptor> = error("No attachment fixture")
            override suspend fun registered(id: AttachmentId): AttachmentDescriptor = error("No attachment fixture")
        }
    }
}
