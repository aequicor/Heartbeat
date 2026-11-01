package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DeepSeekPromptInputsTest {
    @Test
    fun `images use canonical base64 ACP blocks without exposing resource names`() = runTest {
        val reference = ResourceRef("attachment", "image/png")
        val resolver = ResourceResolver { ref ->
            ResolvedResource("private.png", ref.mediaType, "image".encodeToByteArray())
        }
        val request = PromptRequest(
            RequestId("request"),
            listOf(ContentPart.Text("Describe"), ContentPart.Image(reference)),
        )

        val blocks = dshPromptInputs(request, ImageSupport, resolver)

        assertEquals("Describe", blocks[0]["text"]?.jsonPrimitive?.content)
        assertEquals("image", blocks[1]["type"]?.jsonPrimitive?.content)
        assertEquals("aW1hZ2U=", blocks[1]["data"]?.jsonPrimitive?.content)
        assertEquals("image/png", blocks[1]["mimeType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `images are rejected when the connection did not advertise them`() = runTest {
        val failed = assertFailsWith<EngineException> {
            dshPromptInputs(
                PromptRequest(RequestId("request"), listOf(ContentPart.Image(ResourceRef("attachment", "image/png")))),
                PromptInputSupport.TextDocuments,
                ResourceResolver { null },
            )
        }

        assertIs<EngineFailure.Request>(failed.failure)
        assertEquals(RequestFailureReason.UnsupportedContent, (failed.failure as EngineFailure.Request).reason)
    }

    @Test
    fun `resolved media type must match the reference`() = runTest {
        val failed = assertFailsWith<EngineException> {
            dshPromptInputs(
                PromptRequest(RequestId("request"), listOf(ContentPart.Image(ResourceRef("attachment", "image/png")))),
                ImageSupport,
                ResourceResolver { ResolvedResource("photo.jpg", "image/jpeg", "image".encodeToByteArray()) },
            )
        }

        assertIs<EngineFailure.Request>(failed.failure)
    }

    private companion object {
        val ImageSupport = PromptInputSupport(imageMediaTypes = setOf("image/png"))
    }
}
