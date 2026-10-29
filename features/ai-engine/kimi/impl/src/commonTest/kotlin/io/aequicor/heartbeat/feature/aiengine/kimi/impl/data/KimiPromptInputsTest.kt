package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class KimiPromptInputsTest {
    @Test
    fun `images use ACP base64 content without exposing resource names`() = runTest {
        val reference = ResourceRef("attachment", "image/png")
        val resolver = ResourceResolver { ref ->
            ResolvedResource("private.png", ref.mediaType, "image".encodeToByteArray())
        }
        val request = PromptRequest(
            RequestId("request"),
            listOf(ContentPart.Text("Describe"), ContentPart.Image(reference)),
        )

        val blocks = kimiPromptInputs(request, KimiInputSupport, resolver)

        assertEquals("Describe", blocks[0]["text"]?.jsonPrimitive?.content)
        assertEquals("image", blocks[1]["type"]?.jsonPrimitive?.content)
        assertEquals("aW1hZ2U=", blocks[1]["data"]?.jsonPrimitive?.content)
        assertEquals("image/png", blocks[1]["mimeType"]?.jsonPrimitive?.content)
    }

    private companion object {
        val KimiInputSupport = PromptInputSupport.TextDocuments.copy(imageMediaTypes = setOf("image/png"))
    }
}
