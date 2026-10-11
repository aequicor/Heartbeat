package io.aequicor.heartbeat.feature.researchchat.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.attachments.api.AttachmentId
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatIntent
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResource
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceKind
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchAttachments
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

/**
 * Validates imported bytes and resolves registered attachments without owning research execution.
 */
internal class ResearchSourceImporter(private val search: SearchEngine, private val attachments: ResearchAttachments) {
    private val log = Log.tag("ResearchSourceImporter")

    /** A rejected legacy source remains removable and never hides the other saved conversations. */
    suspend fun migrate(source: ResearchResource): ResearchResource = try {
        attachments.persist(source, LegacyResearchSupport, migration = true)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(IllegalStateException(error::class.simpleName)) {
            "Legacy source migration failed"
        }
        source.copy(hasAttachmentError = true)
    }

    suspend fun importSource(input: ResearchChatIntent.Public.AddResource): ResearchResource {
        val value = input.value.trim()
        val title = input.title.trim()
        if (value.startsWith("attachment:")) {
            val file = attachments.registered(AttachmentId(value.substringAfter(':')))
            return ResearchResource(
                newId(),
                title.ifBlank { file.name },
                input.kind,
                file.resource.id,
                file.mediaType,
                attachmentId = file.id,
                attachmentSizeBytes = file.sizeBytes,
            )
        }
        return importValue(input, value, title)
    }

    private suspend fun importValue(
        input: ResearchChatIntent.Public.AddResource,
        value: String,
        title: String,
    ): ResearchResource = when (input.kind) {
        ResearchResourceKind.Website -> {
            require(isWebUrl(value)) { "An HTTP(S) source is required" }
            val page = search.fetch(value)
            require(page.text.isNotBlank()) { "Empty website content" }
            ResearchResource(
                newId(),
                title.ifBlank { page.title ?: value },
                input.kind,
                value,
                "text/html",
                page.text,
            )
        }

        ResearchResourceKind.Document -> {
            val mediaType = input.mediaType ?: "text/plain"
            require(mediaType in setOf("text/plain", "text/markdown", "application/pdf")) {
                "Unsupported document"
            }
            require(
                mediaType != "application/pdf" ||
                    value.startsWith("data:application/pdf;base64,"),
            ) {
                "A PDF must contain imported bytes"
            }
            ResearchResource(
                newId(),
                title.ifBlank { "Document" },
                input.kind,
                value,
                mediaType,
            )
        }

        ResearchResourceKind.Image -> {
            require(value.startsWith("https://") || value.startsWith("data:image/")) {
                "Unsupported image source"
            }
            val mediaType =
                input.mediaType
                    ?: value
                        .takeIf { it.startsWith("data:") }
                        ?.substringAfter("data:")
                        ?.substringBefore(';')
                    ?: "image/jpeg"
            require(mediaType in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) {
                "Unsupported image"
            }
            ResearchResource(newId(), title.ifBlank { "Image" }, input.kind, value, mediaType)
        }
    }

    private fun newId(): String = Uuid.random().toString()
}
