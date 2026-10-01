package io.aequicor.heartbeat.feature.attachments.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.attachments.api.AttachmentId
import io.aequicor.heartbeat.feature.attachments.impl.domain.AttachmentStorage

/** Resolves registered resources only in this profile; transport paths never become resource identifiers. */
@ContributesBinding(ProfileScope::class, priority = 1)
@Inject
internal class ProfileAttachmentResolver(private val storage: AttachmentStorage) : ResourceResolver {
    override suspend fun resolve(reference: ResourceRef): ResolvedResource? {
        if (!reference.id.startsWith(PREFIX)) return null
        val resource = storage.read(AttachmentId(reference.id.removePrefix(PREFIX)))
        require(resource.mediaType == reference.mediaType) { "Attachment MIME mismatch" }
        return resource
    }

    private companion object {
        const val PREFIX = "attachment:"
    }
}
