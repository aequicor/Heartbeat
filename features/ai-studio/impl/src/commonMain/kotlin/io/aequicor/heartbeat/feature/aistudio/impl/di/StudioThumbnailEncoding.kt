package io.aequicor.heartbeat.feature.aistudio.impl.di

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.ds.components.createAttachmentImageThumbnail
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioThumbnailEncoder

/** Pure DS image processing bound at the composition root; data never imports UI components. */
@Inject
@ContributesBinding(AiStudioScope::class)
internal class StudioThumbnailEncoding : StudioThumbnailEncoder {
    override fun encode(bytes: ByteArray): ByteArray = createAttachmentImageThumbnail(bytes)
}
