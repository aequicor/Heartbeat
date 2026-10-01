package io.aequicor.heartbeat.feature.researchchat.impl.di

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.ds.components.createAttachmentImageThumbnail
import io.aequicor.heartbeat.feature.researchchat.impl.di.scope.ResearchChatScope
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchThumbnailEncoder

/** Connects pure presentation processing to the platform image decoder without exposing Compose. */
@ContributesBinding(ResearchChatScope::class)
@Inject
internal class DsResearchThumbnailEncoder : ResearchThumbnailEncoder {
    override fun encode(bytes: ByteArray): ByteArray = createAttachmentImageThumbnail(bytes)
}
