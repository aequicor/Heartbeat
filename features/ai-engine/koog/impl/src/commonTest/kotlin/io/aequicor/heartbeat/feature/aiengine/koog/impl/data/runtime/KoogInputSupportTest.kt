package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KoogInputSupportTest {
    @Test
    fun `owned references are resolved only for the transport and originals are unchanged`() = runTest {
        val source = ContentPart.Image(ResourceRef("attachment:image", "image/png"))
        val support = PromptInputSupport(imageMediaTypes = setOf("image/png"))
        val resolved = resolveKoogInputs(
            listOf(source),
            support,
            ResourceResolver {
                ResolvedResource("image.png", "image/png", "image".encodeToByteArray())
            },
        )
        assertEquals("attachment:image", source.resource.id)
        assertEquals("data:image/png;base64,aW1hZ2U=", (resolved.single() as ContentPart.Image).resource.id)
    }

    @Test
    fun `unknown model and total input limits reject before acceptance`() = runTest {
        val source = ContentPart.Image(ResourceRef("attachment:image", "image/png"))
        val resolver = ResourceResolver { ResolvedResource("image.png", "image/png", ByteArray(5)) }
        assertFailsWith<EngineException> {
            resolveKoogInputs(
                listOf(source),
                PromptInputSupport.TextDocuments,
                resolver,
            )
        }
        assertFailsWith<EngineException> {
            resolveKoogInputs(
                listOf(source, source),
                PromptInputSupport(imageMediaTypes = setOf("image/png"), maxTotalBytes = 8),
                resolver,
            )
        }
    }

    @Test
    fun `file aggregate quota excludes separately bounded generated context`() = runTest {
        val megabyte = 1024 * 1024
        val files = listOf(10, 10, 5).mapIndexed { index, size ->
            ContentPart.Resource(ResourceRef("attachment:file-$index", "text/plain")) to ByteArray(size * megabyte)
        }
        val resolver = ResourceResolver { reference ->
            files.firstOrNull { it.first.resource == reference }?.let {
                ResolvedResource("file.txt", reference.mediaType, it.second)
            }
        }
        val header = ContentPart.Resource(ResourceRef("data:text/plain;base64,Y29udGV4dA==", "text/plain"))
        assertEquals(
            4,
            resolveKoogInputs(files.map { it.first } + header, PromptInputSupport.TextDocuments, resolver).size,
        )
        assertFailsWith<EngineException> {
            resolveKoogInputs(
                files.map { it.first } + files.last().first,
                PromptInputSupport.TextDocuments,
                resolver,
            )
        }
        assertFailsWith<EngineException> {
            resolveKoogInputs(
                List(2) {
                    ContentPart.Resource(
                        ResourceRef(
                            "data:text/plain;base64," + kotlin.io.encoding.Base64.encode(ByteArray(6 * megabyte)),
                            "text/plain",
                        ),
                    )
                },
                PromptInputSupport.TextDocuments,
                ResourceResolver { null },
            )
        }
    }
}
