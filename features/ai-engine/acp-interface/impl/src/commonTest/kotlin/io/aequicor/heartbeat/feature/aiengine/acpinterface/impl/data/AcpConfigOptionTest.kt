package io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.data

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AcpConfigOptionTest {
    @Test
    fun `set config option sends wire frame between turns`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        h.session()
        val task = backgroundScope.async { h.client.setConfigOption("session", "model", "kimi-for-coding") }
        val request = h.outgoing("session/set_config_option")
        val params = request.obj("params")
        assertEquals("session", params.string("sessionId"))
        assertEquals("model", params.string("configId"))
        assertEquals("kimi-for-coding", params.string("value"))
        h.reply(request, """{}""")
        task.await()
        h.client.close()
    }

    @Test
    fun `set config option submits while another session awaits its prompt response`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        h.session("one")
        h.session("two")
        val prompt = backgroundScope.async { h.client.prompt("one", "hello") }
        val promptRequest = h.outgoing("session/prompt")
        // The config frame must leave the admission barrier without waiting for the other session's round trip.
        val configure = backgroundScope.async { h.client.setConfigOption("two", "model", "kimi-for-coding") }
        val configRequest = h.outgoing("session/set_config_option")
        assertEquals("two", configRequest.obj("params").string("sessionId"))
        h.reply(configRequest, """{}""")
        configure.await()
        h.reply(promptRequest, """{"stopReason":"end_turn"}""")
        assertEquals("end_turn", prompt.await().stopReason)
        h.client.close()
    }

    @Test
    fun `set config option rejects a busy and an unknown session`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        h.session()
        val prompt = backgroundScope.async { h.client.prompt("session", "hello") }
        val promptRequest = h.outgoing("session/prompt")
        assertFailsWith<IllegalStateException> { h.client.setConfigOption("session", "model", "x") }
        assertFailsWith<IllegalArgumentException> { h.client.setConfigOption("missing", "model", "x") }
        h.reply(promptRequest, """{"stopReason":"end_turn"}""")
        prompt.await()
        h.client.close()
    }

    @Test
    fun `multimodal prompt serializes every content block`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        h.session()
        val content = listOf(
            JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive("look"))),
            JsonObject(mapOf("type" to JsonPrimitive("image"), "uri" to JsonPrimitive("file:///a.png"))),
        )
        val prompt = backgroundScope.async { h.client.prompt("session", content) }
        val request = h.outgoing("session/prompt")
        val blocks = request.obj("params").getValue("prompt").jsonArray
        assertEquals(2, blocks.size)
        assertEquals("text", (blocks[0] as JsonObject).string("type"))
        assertEquals("look", (blocks[0] as JsonObject).string("text"))
        assertEquals("image", (blocks[1] as JsonObject).string("type"))
        h.reply(request, """{"stopReason":"end_turn"}""")
        prompt.await()
        h.client.close()
    }

    @Test
    fun `empty prompt content is rejected before reaching the wire`() = runTest {
        val h = AcpTestHarness(this)
        h.initialize()
        h.session()
        assertFailsWith<IllegalArgumentException> { h.client.prompt("session", emptyList()) }
        h.client.close()
    }
}
