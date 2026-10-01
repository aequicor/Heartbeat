package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import kotlinx.coroutines.flow.Flow

/** Small transient representation; original file bytes and stable resource identity stay outside UI state. */
data class StudioAttachmentPreview(
    val imageBytes: ByteArray? = null,
    val documentSnippet: String? = null,
    val isUnavailable: Boolean = false,
) {
    override fun toString(): String =
        "StudioAttachmentPreview(imageBytes=${imageBytes?.size ?: 0}, error=$isUnavailable)"
}

/** Observes only composed attachment rows. Removing a row cancels its unresolved read or image encoding. */
interface StudioAttachmentPreviews {
    /** Emits bounded previews by original resource URI; the implementation caches them within this feature scope. */
    fun observe(resources: List<ResourceRef>): Flow<Map<String, StudioAttachmentPreview>>
}
