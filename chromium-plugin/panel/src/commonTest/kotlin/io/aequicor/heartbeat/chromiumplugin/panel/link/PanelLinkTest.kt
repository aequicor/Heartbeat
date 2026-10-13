package io.aequicor.heartbeat.chromiumplugin.panel.link

import io.aequicor.heartbeat.chromiumplugin.panel.FakePanelHost
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.BridgeErrorCode
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
    fun `the latest status and agent control are kept and replies complete their calls`() = runTest {
        val host = FakePanelHost()
        host.deliver("""{"type":"status","status":"ready","features":{"studio":true}}""")
        val link = PanelLink(host)
        host.deliver("""{"type":"unknown_kind_of_a_newer_extension"}""")
        host.deliver("""{"type":"agent","isAllowed":true,"controlled":2}""")

        val call = async { link.call("studio.intent", JsonObject(mapOf("view" to JsonPrimitive("v")))) }
        runCurrent()
        val id = host.posted.single().getValue("id").jsonPrimitive.int
        host.deliver("""{"type":"reply","id":$id,"result":{"ack":1}}""")

        assertEquals(JsonObject(mapOf("ack" to JsonPrimitive(1))), call.await())
        assertEquals(PanelInbound.Status("ready", PanelFeatures(isStudio = true)), link.status.value)
        assertEquals(PanelInbound.Agent(isAllowed = true, controlled = 2), link.agent.value)
    }

    @Test
    fun `a watch receives its own events and failures and is closed when no longer collected`() = runTest {
        val host = FakePanelHost()
        val link = PanelLink(host)
        val received = mutableListOf<PanelInbound.ForWatch>()

        val watching = launch { link.watch("studio", "studio.watch", JsonObject(emptyMap())).toList(received) }
        runCurrent()
        host.deliver("""{"type":"event","key":"other","event":"chat.changed","params":{}}""")
        host.deliver("""{"type":"event","key":"studio","event":"studio.changed","params":{"view":"v"}}""")
        host.deliver("""{"type":"watchFailed","key":"studio","error":{"code":"busy"}}""")
        runCurrent()
        watching.cancel()
        runCurrent()

        assertEquals(
            listOf(
                PanelInbound.Event("studio", "studio.changed", JsonObject(mapOf("view" to JsonPrimitive("v")))),
                PanelInbound.WatchFailed("studio", PanelError(BridgeErrorCode.BUSY)),
            ),
            received,
        )
        assertEquals(listOf("watch", "unwatch"), host.posted.map { it.getValue("type").jsonPrimitive.content })
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
