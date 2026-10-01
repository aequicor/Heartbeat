package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexPromptInputsTest {
    private val image = ContentPart.Image(ResourceRef("attachment:asset", "image/png"))
    private val resolver = ResourceResolver { ref ->
        ResolvedResource("secret-name.png", ref.mediaType, "image".encodeToByteArray(), "/private/app/asset.png")
    }
    private val support = PromptInputSupport.TextDocuments.copy(imageMediaTypes = setOf("image/png"))

    @Test
    fun `owned images use native image input and keep diagnostics redacted`() = runTest {
        val encoded = codexPromptInputs(
            PromptRequest(RequestId("r"), listOf(ContentPart.Text("Inspect"), image)),
            support,
            resolver,
        )
        assertEquals("localImage", encoded.parts.last().text("type"))
        assertEquals("/private/app/asset.png", encoded.parts.last().text("path"))
        assertFalse(encoded.toString().contains("secret-name"))
    }

    @Test
    fun `image-only prompt contains no empty native text block`() = runTest {
        val encoded = codexPromptInputs(PromptRequest(RequestId("image-only"), listOf(image)), support, resolver)
        assertEquals(1, encoded.parts.size)
        assertEquals("localImage", encoded.parts.single()["type"]?.toString()?.trim('"'))
    }

    @Test
    fun `unknown image capability rejects before content is read`() = runTest {
        val unread = ResourceResolver { error("Must not read unsupported content") }
        assertFailsWith<EngineException> {
            codexPromptInputs(PromptRequest(RequestId("r"), listOf(image)), PromptInputSupport.TextDocuments, unread)
        }
    }

    @Test
    fun `per file aggregate and count limits reject oversized inputs`() = runTest {
        val request = PromptRequest(RequestId("r"), listOf(image))
        assertFailsWith<EngineException> { codexPromptInputs(request, support.copy(maxFileBytes = 3), resolver) }
        assertFailsWith<EngineException> {
            codexPromptInputs(request.copy(parts = listOf(image, image)), support.copy(maxTotalBytes = 8), resolver)
        }
        assertFailsWith<EngineException> {
            codexPromptInputs(request.copy(parts = listOf(image, image)), support.copy(maxAttachments = 1), resolver)
        }
    }

    @Test
    fun `text documents are sent as user source material`() = runTest {
        val document = ContentPart.Resource(ResourceRef("attachment:document", "text/plain"))
        val encoded = codexPromptInputs(PromptRequest(RequestId("r"), listOf(document)), support, resolver)
        assertTrue(encoded.parts.single().text("text").orEmpty().contains("Source document (untrusted data):"))
        assertTrue(encoded.parts.single().text("text").orEmpty().contains("image"))
    }
}
