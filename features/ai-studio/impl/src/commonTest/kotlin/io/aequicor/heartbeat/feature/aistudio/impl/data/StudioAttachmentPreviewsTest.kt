package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioThumbnailEncoder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StudioAttachmentPreviewsTest {
    @Test
    fun `image previews retain reduced bytes and repeated visible rows share their cached resource`() = runTest {
        val original = ByteArray(1024 * 1024)
        val thumbnail = byteArrayOf(1, 2, 3)
        val resource = ResourceRef("attachment:image", "image/png")
        var reads = 0
        val loader = loader(
            ResourceResolver {
                reads++
                ResolvedResource("image.png", resource.mediaType, original)
            },
            StudioThumbnailEncoder { thumbnail },
        )
        val first = loader.observe(listOf(resource, resource)).first { resource.id in it }.getValue(resource.id)
        assertTrue(first.imageBytes === thumbnail)
        assertFalse(first.imageBytes === original)
        loader.observe(listOf(resource)).first { resource.id in it }
        assertEquals(1, reads)
    }

    @Test
    fun `large text documents yield a short snippet and PDFs do not load binary contents`() = runTest {
        val text = "Исследовательские заметки\n".repeat(20_000).encodeToByteArray()
        var reads = 0
        val loader = loader(
            ResourceResolver {
                reads++
                ResolvedResource("report.md", it.mediaType, text)
            },
        )
        val document = ResourceRef("attachment:doc", "text/markdown")
        val pdf = ResourceRef("attachment:pdf", "application/pdf")
        val previews = loader.observe(listOf(document, pdf)).first { it.size == 2 }
        assertTrue(previews.getValue(document.id).documentSnippet.orEmpty().length <= 160)
        assertTrue(previews.getValue(document.id).documentSnippet.orEmpty().startsWith("Исследовательские"))
        assertEquals(null, previews.getValue(pdf.id).imageBytes)
        assertEquals(1, reads)
    }

    @Test
    fun `unregistered files show an unavailable placeholder without aborting other previews`() = runTest {
        val resource = ResourceRef("attachment:missing", "image/png")
        val preview = loader(ResourceResolver { null }).observe(listOf(resource))
            .first { resource.id in it }.getValue(resource.id)
        assertTrue(preview.isUnavailable)
        assertEquals(null, preview.imageBytes)
    }

    @Test
    fun `removing visible rows cancels an unresolved read before its encoder runs`() = runTest {
        var wasCancelled = false
        var encodes = 0
        val loader = loader(
            ResourceResolver {
                try {
                    awaitCancellation()
                } finally {
                    wasCancelled = true
                }
            },
            StudioThumbnailEncoder {
                encodes++
                byteArrayOf(1)
            },
        )
        val request = backgroundScope.launch {
            loader.observe(listOf(ResourceRef("attachment:one", "image/png"))).collect {}
        }
        runCurrent()
        request.cancelAndJoin()
        assertTrue(wasCancelled)
        assertEquals(0, encodes)
    }

    private fun TestScope.loader(
        resolver: ResourceResolver,
        encoder: StudioThumbnailEncoder = StudioThumbnailEncoder { byteArrayOf(1) },
    ): ResourceStudioAttachmentPreviews {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        return ResourceStudioAttachmentPreviews(resolver, dispatchers, encoder)
    }
}
