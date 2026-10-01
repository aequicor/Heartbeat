package io.aequicor.heartbeat.feature.researchchat.impl.presentation.store

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchThumbnailEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Only small derived bytes live in the screen; source content and native locations never enter it. */
internal data class ResearchThumbnailUi(
    val imageBytes: ByteArray? = null,
    val documentSnippet: String? = null,
    val isUnavailable: Boolean = false,
) {
    override fun toString(): String = "ResearchThumbnailUi(unavailable=$isUnavailable)"
}

/** Visible source rows own cancellable preview reads. Disposed rows release every retained byte. */
internal class ResearchThumbnails(
    private val scope: CoroutineScope,
    private val resolver: ResourceResolver?,
    private val dispatchers: DispatcherProvider?,
    private val encoder: ResearchThumbnailEncoder?,
) {
    private val log = Log.tag("Research/Thumbnails")
    private val jobs = mutableMapOf<String, Job>()
    private val permits = Semaphore(2)
    private val values = MutableStateFlow<Map<String, ResearchThumbnailUi>>(emptyMap())
    val state = values.asStateFlow()

    fun load(key: String, reference: ResourceRef) {
        if (key in jobs || key in values.value) return
        val reader = resolver ?: return
        val threads = dispatchers ?: return
        val images = encoder ?: return
        jobs[key] = scope.launch {
            val preview = try {
                permits.withPermit {
                    val resource = reader.resolve(reference)
                    if (resource == null) {
                        ResearchThumbnailUi(isUnavailable = true)
                    } else {
                        withContext(threads.default) {
                            when (resource.mediaType) {
                                "image/png", "image/jpeg", "image/gif", "image/webp" -> ResearchThumbnailUi(
                                    imageBytes = images.encode(resource.bytes),
                                )

                                "text/plain", "text/markdown" -> ResearchThumbnailUi(
                                    documentSnippet = resource.bytes.copyOfRange(0, minOf(512, resource.bytes.size))
                                        .decodeToString().take(160),
                                )

                                else -> ResearchThumbnailUi()
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(IllegalStateException(error::class.simpleName)) { "Source thumbnail failed" }
                ResearchThumbnailUi(isUnavailable = true)
            }
            values.update { it + (key to preview) }
        }
    }

    fun release(key: String) {
        jobs.remove(key)?.cancel()
        log.d { "Visible source preview released" }
        values.update { it - key }
    }
}
