package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioAttachmentPreview
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioAttachmentPreviews
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioThumbnailEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Feature-owned LRU: at most 32 reduced previews (8 MiB), with no original bytes or cross-profile lookup. */
@Inject
@SingleIn(AiStudioScope::class)
@ContributesBinding(AiStudioScope::class)
internal class ResourceStudioAttachmentPreviews(
    private val resolver: ResourceResolver,
    private val dispatchers: DispatcherProvider,
    private val encoder: StudioThumbnailEncoder,
) : StudioAttachmentPreviews {
    private val log = Log.tag("Studio/AttachmentPreviews")
    private val cache = LinkedHashMap<String, StudioAttachmentPreview>()
    private val cacheLock = Mutex()
    private val decoding = Semaphore(MAX_CONCURRENT_PREVIEWS)

    override fun observe(resources: List<ResourceRef>): Flow<Map<String, StudioAttachmentPreview>> = channelFlow {
        val shown = resources.distinctBy { it.id }.take(MAX_CACHED_PREVIEWS)
        val results = mutableMapOf<String, StudioAttachmentPreview>()
        val resultsLock = Mutex()
        log.d { "Observe visible attachment previews count=${shown.size}" }
        send(emptyMap())
        shown.forEach { resource ->
            launch {
                val preview = decoding.withPermit { load(resource) }
                resultsLock.withLock {
                    results[resource.id] = preview
                    send(results.toMap())
                }
            }
        }
    }

    private suspend fun load(resource: ResourceRef): StudioAttachmentPreview {
        currentCoroutineContext().ensureActive()
        cacheLock.withLock {
            cache.remove(resource.id)?.let {
                cache[resource.id] = it
                return it
            }
        }
        val preview = try {
            readPreview(resource)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(IllegalStateException(error::class.simpleName.orEmpty())) { "Attachment thumbnail unavailable" }
            StudioAttachmentPreview(isUnavailable = true)
        }
        currentCoroutineContext().ensureActive()
        cacheLock.withLock {
            cache[resource.id] = preview
            while (cache.size > MAX_CACHED_PREVIEWS) cache.remove(cache.keys.first())
        }
        return preview
    }

    private suspend fun readPreview(resource: ResourceRef): StudioAttachmentPreview {
        if (resource.mediaType !in TEXT_TYPES && !resource.mediaType.startsWith("image/")) {
            return StudioAttachmentPreview()
        }
        val file = requireNotNull(resolver.resolve(resource)) { "Attachment preview resource is unavailable" }
        currentCoroutineContext().ensureActive()
        return withContext(dispatchers.default) {
            if (resource.mediaType.startsWith("image/")) {
                StudioAttachmentPreview(imageBytes = encoder.encode(file.bytes))
            } else {
                val snippet = file.bytes.decodeToString(endIndex = minOf(file.bytes.size, TEXT_PREFIX_BYTES))
                    .replace(WHITESPACE, " ").trim().take(TEXT_SNIPPET_CHARACTERS)
                StudioAttachmentPreview(documentSnippet = snippet)
            }
        }
    }
}

private const val MAX_CACHED_PREVIEWS = 32
private const val MAX_CONCURRENT_PREVIEWS = 2
private const val TEXT_PREFIX_BYTES = 1024
private const val TEXT_SNIPPET_CHARACTERS = 160
private val TEXT_TYPES = setOf("text/plain", "text/markdown")
private val WHITESPACE = Regex("\\s+")
