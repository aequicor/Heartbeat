package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudePromptInputsTest {
    private val image = ContentPart.Image(ResourceRef("attachment:asset", "image/png"))
    private val resolver = ResourceResolver { ref ->
        ResolvedResource("secret-name.png", ref.mediaType, "image".encodeToByteArray(), "/private/app/asset.png")
    }
    private val support = PromptInputSupport.TextDocuments.copy(imageMediaTypes = setOf("image/png"))

    @Test
    fun `owned images use native image input and keep diagnostics redacted`() = runTest {
        val encoded = claudePromptInputs(
            PromptRequest(RequestId("r"), listOf(ContentPart.Text("Inspect"), image)),
            support,
            resolver,
        )
        val image = encoded.blocks.last()
        assertEquals("image", image["type"]?.jsonPrimitive?.content)
        assertEquals("aW1hZ2U=", image["source"]?.jsonObject?.get("data")?.jsonPrimitive?.content)
        assertFalse(encoded.toString().contains("aW1hZ2U="))
    }

    @Test
    fun `image-only prompt contains no empty native text block`() = runTest {
        val encoded = claudePromptInputs(PromptRequest(RequestId("image-only"), listOf(image)), support, resolver)
        assertEquals(1, encoded.blocks.size)
        assertEquals("image", encoded.blocks.single()["type"]?.toString()?.trim('"'))
    }

    @Test
    fun `an alias takes the vision of the model the CLI resolves it to`() {
        fun alias(value: String, resolved: String?) = kotlinx.serialization.json.buildJsonObject {
            put("value", kotlinx.serialization.json.JsonPrimitive(value))
            resolved?.let { put("resolvedModel", kotlinx.serialization.json.JsonPrimitive(it)) }
        }
        for ((value, resolved) in listOf(
            "default" to "claude-sonnet-5-5",
            "opus" to "claude-opus-5-5",
            "fable" to "claude-fable-5-1",
            "haiku" to "claude-haiku-5-5",
            "claude-sonnet-5" to "claude-sonnet-5",
            "claude-opus-4-8" to "claude-opus-4-8",
        )) {
            kotlin.test.assertTrue(claudeInputSupport(alias(value, resolved), true).accepts("image/png"), value)
        }
        assertFalse(claudeInputSupport(alias("custom", "claude-custom-text-only"), true).accepts("image/png"))
        assertFalse(claudeInputSupport(alias("sonnet", "claude-sonnet-5-5")).accepts("image/png"))
        assertFalse(claudeInputSupport(alias("sonnet", null), true).accepts("image/png"))
    }

    @Test
    fun `the one million context variant keeps the vision of its model and a null resolved model falls back`() {
        fun alias(value: String, resolved: kotlinx.serialization.json.JsonElement) =
            kotlinx.serialization.json.buildJsonObject {
                put("value", kotlinx.serialization.json.JsonPrimitive(value))
                put("resolvedModel", resolved)
            }
        fun resolved(id: String) = kotlinx.serialization.json.JsonPrimitive(id)
        fun accepts(model: kotlinx.serialization.json.JsonObject) = claudeInputSupport(model, true).accepts("image/png")
        kotlin.test.assertTrue(accepts(alias("default", resolved("claude-opus-5-5[1m]"))))
        kotlin.test.assertTrue(accepts(alias("sonnet[1m]", resolved("claude-sonnet-5-5[1M]"))))
        // The suffix is dropped, not the model check: an unknown model stays without images.
        assertFalse(accepts(alias("custom", resolved("claude-custom-text-only[1m]"))))
        // A JSON null is an absent field, never the model id "null".
        kotlin.test.assertTrue(accepts(alias("claude-sonnet-4-6", kotlinx.serialization.json.JsonNull)))
    }

    @Test
    fun `custom suffix and unconfirmed origin never inherit official model vision`() {
        fun nativeModel(id: String, vision: Boolean? = null) = kotlinx.serialization.json.buildJsonObject {
            put("value", kotlinx.serialization.json.JsonPrimitive(id))
            vision?.let { put("supportsVision", kotlinx.serialization.json.JsonPrimitive(it)) }
        }
        assertFalse(claudeInputSupport(nativeModel("claude-sonnet-4-custom-text-only"), true).accepts("image/png"))
        assertFalse(claudeInputSupport(nativeModel("claude-sonnet-4-6-custom-text-only"), true).accepts("image/png"))
        assertFalse(claudeInputSupport(nativeModel("sonnet"), true).accepts("image/png"))
        assertFalse(claudeInputSupport(nativeModel("claude-sonnet-4-6")).accepts("image/png"))
        kotlin.test.assertTrue(claudeInputSupport(nativeModel("claude-sonnet-4-6"), true).accepts("image/png"))
        kotlin.test.assertTrue(claudeInputSupport(nativeModel("custom-model", true)).accepts("image/png"))
        assertFalse(claudeInputSupport(nativeModel("claude-sonnet-4-6", false), true).accepts("image/png"))
    }

    @Test
    fun `unknown image capability rejects before content is read`() = runTest {
        val unread = ResourceResolver { error("Must not read unsupported content") }
        assertFailsWith<EngineException> {
            claudePromptInputs(PromptRequest(RequestId("r"), listOf(image)), PromptInputSupport.TextDocuments, unread)
        }
    }

    @Test
    fun `per file aggregate and count limits reject oversized inputs`() = runTest {
        val request = PromptRequest(RequestId("r"), listOf(image))
        assertFailsWith<EngineException> { claudePromptInputs(request, support.copy(maxFileBytes = 3), resolver) }
        assertFailsWith<EngineException> {
            claudePromptInputs(request.copy(parts = listOf(image, image)), support.copy(maxTotalBytes = 8), resolver)
        }
        assertFailsWith<EngineException> {
            claudePromptInputs(request.copy(parts = listOf(image, image)), support.copy(maxAttachments = 1), resolver)
        }
    }

    @Test
    fun `text documents are sent as user source material`() = runTest {
        val document = ContentPart.Resource(ResourceRef("attachment:document", "text/plain"))
        val encoded = claudePromptInputs(PromptRequest(RequestId("r"), listOf(document)), support, resolver)
        assertTrue(encoded.text.contains("Source document (untrusted data):"))
        assertTrue(encoded.text.contains("image"))
    }
}
