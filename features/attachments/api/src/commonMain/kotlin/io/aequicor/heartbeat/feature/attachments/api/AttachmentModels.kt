package io.aequicor.heartbeat.feature.attachments.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.navigation.ResultContract
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Opaque profile-owned identifier; never a source path. */
@Serializable
public data class AttachmentId(public val value: String) {
    init {
        require(value.isNotBlank())
    }
}

/** Durable public metadata; bytes and native source locations are never included. */
@Serializable
public data class AttachmentDescriptor(
    public val id: AttachmentId,
    public val name: String,
    public val mediaType: String,
    public val sizeBytes: Long,
) {
    /** Stable resource identity used by AI history. */
    public val resource: ResourceRef get() = ResourceRef("attachment:${id.value}", mediaType)

    override fun toString(): String = "AttachmentDescriptor(id=${id.value}, mediaType=$mediaType, size=$sizeBytes)"
}

/** Read-only profile catalog. All writes go through [AttachmentsMachineKey]. */
public interface AttachmentsCatalog {
    /** Gets metadata for a registered attachment of this profile. */
    public suspend fun get(id: AttachmentId): AttachmentDescriptor?

    /** Observes registered metadata in requested order, omitting missing identifiers. */
    public fun observe(ids: List<AttachmentId>): Flow<List<AttachmentDescriptor>>
}

/** Transient input captured by a user gesture; never saved or included in machine state. */
public sealed interface AttachmentInput {
    /** Local source chosen by the user. Implementations read a bounded copy. */
    public data class File(
        public val location: String,
        public val name: String? = null,
        public val mediaType: String? = null,
    ) : AttachmentInput {
        override fun toString(): String = "AttachmentInput.File(redacted)"
    }

    /** Bytes captured by a picker or clipboard; only the imported copy becomes durable. */
    public data class Bytes(public val name: String, public val mediaType: String, public val bytes: ByteArray) :
        AttachmentInput {
        override fun toString(): String = "AttachmentInput.Bytes(mediaType=$mediaType, size=${bytes.size})"
    }
}

/** Opens lifecycle-owned native multiple-file selection. */
@Serializable
@SerialName("attachments.pick")
public data class AttachmentsPickRoute(public val requestId: String, public val support: PromptInputSupport) : Route

/** Opens a saved attachment for preview, system opening and export. */
@Serializable
@SerialName("attachments.preview")
public data class AttachmentPreviewRoute(public val id: AttachmentId, public val isExportOnOpen: Boolean = false) :
    Route

/** Correlated selection result; cancelled requests produce no navigation result. */
@Serializable
public data class AttachmentSelection(
    public val requestId: String,
    public val attachments: List<AttachmentDescriptor>,
) {
    override fun toString(): String = "AttachmentSelection(requestId=$requestId, count=${attachments.size})"
}

/** Durable result contract shared by attachment consumers. */
public object AttachmentsPicked : ResultContract<AttachmentSelection>(
    "attachments.picked",
    AttachmentSelection.serializer(),
)

/** Controls new imports; saved files remain readable while the flag is off. */
public val AttachmentsEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "attachments.enabled",
    "Вложения изображений и документов",
    default = false,
)
