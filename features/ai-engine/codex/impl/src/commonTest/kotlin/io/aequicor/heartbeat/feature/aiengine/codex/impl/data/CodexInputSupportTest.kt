package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodexInputSupportTest {
    @Test
    fun `legacy model metadata preserves the native default image support`() {
        val support = codexInputSupport(json("model" to "gpt-6.1-sol".json()))

        assertEquals(setOf("image/png", "image/jpeg", "image/webp", "image/gif"), support.imageMediaTypes)
        assertEquals(setOf("text/plain", "text/markdown"), support.resourceMediaTypes)
    }

    @Test
    fun `legacy discovery offers images to the composer and sends an image only prompt`() = runTest {
        val fixture = Fixture(this)
        fixture.modelList = listOf(json("model" to fixture.target.model.value.json()))
        fixture.resources = ResourceResolver {
            ResolvedResource("photo.png", "image/png", byteArrayOf(1), "/private/app/photo.png")
        }
        val model = fixture.runtime.models(fixture.target.binding).single()
        assertTrue(model.inputSupport.accepts("image/png"))
        val session = fixture.open()

        session.feature(SendsPrompts).send(
            PromptRequest(RequestId("photo"), listOf(ContentPart.Image(ResourceRef("attachment:photo", "image/png")))),
        )

        val input = fixture.wire.written.single { it.text("method") == "turn/start" }.obj("params")
            .array("input").single() as JsonObject
        assertEquals("localImage", input.text("type"))
        assertEquals("/private/app/photo.png", input.text("path"))
    }

    @Test
    fun `explicit text only and empty modalities keep images unavailable`() {
        for (modalities in listOf(JsonArray(listOf("text".json())), JsonArray(emptyList()))) {
            val support = codexInputSupport(json("inputModalities" to modalities))

            assertTrue(support.imageMediaTypes.isEmpty())
            assertEquals(setOf("text/plain", "text/markdown"), support.resourceMediaTypes)
        }
    }

    @Test
    fun `explicit image modality enables photo selection`() {
        val support = codexInputSupport(json("inputModalities" to JsonArray(listOf("text".json(), "image".json()))))

        assertTrue(support.accepts("image/png"))
        assertTrue(support.accepts("image/jpeg"))
    }

    @Test
    fun `invalid modality metadata does not inherit the legacy default`() {
        for (modalities in listOf(JsonNull, "image".json(), JsonArray(listOf("audio".json())))) {
            assertTrue(codexInputSupport(json("inputModalities" to modalities)).imageMediaTypes.isEmpty())
        }
    }
}
