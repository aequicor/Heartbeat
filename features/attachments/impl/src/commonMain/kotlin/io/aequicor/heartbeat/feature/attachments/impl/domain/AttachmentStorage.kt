package io.aequicor.heartbeat.feature.attachments.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.attachments.api.AttachmentDescriptor
import io.aequicor.heartbeat.feature.attachments.api.AttachmentId
import io.aequicor.heartbeat.feature.attachments.api.AttachmentInput

/** Private write port used exclusively by machine effects. */
internal interface AttachmentStorage {
    suspend fun prepare()
    suspend fun import(
        inputs: List<AttachmentInput>,
        support: PromptInputSupport,
        deduplicationKey: String? = null,
    ): List<AttachmentDescriptor>
    suspend fun read(id: AttachmentId): ResolvedResource
}

/** Metadata projection shared by catalog without exposing mutation. */
internal interface AttachmentMetadata {
    suspend fun get(id: AttachmentId): AttachmentDescriptor?
    fun observe(ids: List<AttachmentId>): kotlinx.coroutines.flow.Flow<List<AttachmentDescriptor>>
}

/** Native actions receive a UI host only for the lifetime of one suspending operation. */
internal interface AttachmentNativeActions {
    suspend fun choose(host: Any?, support: PromptInputSupport): List<AttachmentInput>?
    suspend fun open(host: Any?, resource: ResolvedResource)
    suspend fun export(host: Any?, resource: ResolvedResource): Boolean
}
