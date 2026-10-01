package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.Serializable

/** Input formats confirmed for one exact model, connection and native adapter. Empty sets mean no support. */
@Serializable
public data class PromptInputSupport(
    public val imageMediaTypes: Set<String> = emptySet(),
    public val resourceMediaTypes: Set<String> = emptySet(),
    public val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    public val maxAttachments: Int = 10,
    public val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) {
    init {
        require(maxFileBytes > 0 && maxAttachments > 0 && maxTotalBytes > 0)
    }

    /** All supported MIME types, independent of the message part used to carry the reference. */
    public val allowedMediaTypes: Set<String> get() = imageMediaTypes + resourceMediaTypes

    /** Whether this exact route supports [mediaType]. */
    public fun accepts(mediaType: String): Boolean = mediaType in allowedMediaTypes

    /** Reusable support of native adapters that serialize UTF-8 documents as user context. */
    public companion object {
        private const val DEFAULT_MAX_FILE_BYTES = 10_485_760L
        private const val DEFAULT_MAX_TOTAL_BYTES = 26_214_400L

        /** Plain text and Markdown need no binary model capability. */
        public val TextDocuments: PromptInputSupport = PromptInputSupport(
            resourceMediaTypes = setOf("text/plain", "text/markdown"),
        )
    }
}

/** Profile-owned resolution of host resources. Unknown namespaces return null; missing owned data fails. */
public fun interface ResourceResolver {
    /** Reads bounded resource content without arbitrary local-file access; main-safe and cancellable. */
    public suspend fun resolve(reference: ResourceRef): ResolvedResource?
}

/** A transient bounded read. Only the original [ResourceRef] belongs in durable histories. */
public data class ResolvedResource(
    public val name: String,
    public val mediaType: String,
    public val bytes: ByteArray,
    /** Stable app-owned path for native APIs requiring a file; never inferred from a reference string. */
    public val localPath: String? = null,
) {
    override fun toString(): String = "ResolvedResource(mediaType=$mediaType, size=${bytes.size})"
}

/** Durable original inputs for native transports whose histories replace host references with paths or text. */
public interface PromptResourceHistory {
    /** Stores bounded original parts under an immutable native turn/item identity; contains no file bytes. */
    public suspend fun remember(session: SessionRef, nativeId: String, parts: List<ContentPart>)

    /** Original parts for this native identity, or null for external/text-only messages. */
    public suspend fun parts(session: SessionRef, nativeId: String): List<ContentPart>?

    /** No persisted source is used by fixtures that do not submit attachments. */
    public companion object {
        /** Inert implementation for isolated adapter fixtures. Production always injects the profile store. */
        public val None: PromptResourceHistory = object : PromptResourceHistory {
            override suspend fun remember(session: SessionRef, nativeId: String, parts: List<ContentPart>) = Unit
            override suspend fun parts(session: SessionRef, nativeId: String): List<ContentPart>? = null
        }
    }
}
