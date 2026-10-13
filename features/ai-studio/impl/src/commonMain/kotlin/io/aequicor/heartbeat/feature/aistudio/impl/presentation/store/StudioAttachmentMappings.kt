package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioAttachmentPreview
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AttachmentPreviewUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AttachmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.InputSupportUi
import io.aequicor.heartbeat.feature.attachments.api.AttachmentDescriptor
import kotlinx.collections.immutable.toImmutableList

internal fun StudioAttachmentPreview.toUi() = AttachmentPreviewUi(imageBytes, documentSnippet, isUnavailable)
internal fun PromptInputSupport.toUi() = InputSupportUi(
    allowedMediaTypes.toImmutableList(),
    imageMediaTypes.toImmutableList(),
    maxFileBytes,
    maxAttachments,
    maxTotalBytes,
)

internal fun InputSupportUi.toDomain() = PromptInputSupport(
    imageMediaTypes.toSet(),
    (mediaTypes - imageMediaTypes.toSet()).toSet(),
    maxFileBytes,
    maxAttachments,
    maxTotalBytes,
)

internal fun AttachmentDescriptor.toUi() = AttachmentUi(id.value, name, mediaType, sizeBytes)
