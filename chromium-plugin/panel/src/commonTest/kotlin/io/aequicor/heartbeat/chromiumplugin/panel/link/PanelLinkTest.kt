package io.aequicor.heartbeat.chromiumplugin.panel.link

import io.aequicor.heartbeat.chromiumplugin.panel.FakePanelHost
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.BridgeErrorCode
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class PanelLinkTest {
    @Test
    fun `messages before the collector starts arrive in order and replies complete their calls`() = runTest {
        val host = FakePanelHost()
        host.deliver("""{"type":"status","status":"connecting"}""")
        val link = PanelLink(host)
        host.deliver("""{"type":"unknown_kind_of_a_newer_extension"}""")
        host.deliver("""{"type":"agent","isAllowed":true,"controlled":2}""")

        val call = async { link.call("chat.stop", JsonObject(mapOf("chatId" to JsonPrimitive("c")))) }
        runCurrent()
        val id = host.posted.single().getValue("id").jsonPrimitive.int
        host.deliver("""{"type":"reply","id":$id,"result":{"stopped":true}}""")

        assertEquals(JsonObject(mapOf("stopped" to JsonPrimitive(true))), call.await())
        assertEquals(
            listOf(PanelInbound.Status("connecting"), PanelInbound.Agent(isAllowed = true, controlled = 2)),
            link.messages.take(2).toList(),
        )
    }

    @Test
    fun `a refusal and a missing reply fail the call with their codes`() = runTest {
        val host = FakePanelHost()
        val link = PanelLink(host, callTimeout = 1.seconds)

        val refused = async { assertFailsWith<PanelCallException> { link.call("chat.send", JsonObject(emptyMap())) } }
        runCurrent()
        val id = host.posted.last().getValue("id").jsonPrimitive.int
        host.deliver("""{"type":"reply","id":$id,"error":{"code":"busy"}}""")

        assertEquals(BridgeErrorCode.BUSY, refused.await().code)
        val timeout = assertFailsWith<PanelCallException> { link.call("chat.send", JsonObject(emptyMap())) }
        assertEquals(BridgeErrorCode.TIMEOUT, timeout.code)
    }
}
