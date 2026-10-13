package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeFramesTest {
    @Test
    fun `request uses the kind discriminator and round trips`() {
        val frame = BridgeFrame.Request("e:1", "hello", buildJsonObject { put("protocol", 1) })

        val payload = encodeFrame(frame)

        val json = BridgeProtocol.json.parseToJsonElement(payload.decodeToString()).jsonObject
        assertEquals(JsonPrimitive("req"), json["kind"])
        assertEquals(frame, decodeFrame(payload))
    }

    @Test
    fun `absent optional fields are omitted and restored as null`() {
        val payload = encodeFrame(BridgeFrame.Response("a:2", result = JsonPrimitive(true)))

        val text = payload.decodeToString()
        assertTrue("error" !in text, text)
        assertEquals(BridgeFrame.Response("a:2", result = JsonPrimitive(true)), decodeFrame(payload))
    }

    @Test
    fun `host frames carry state towards the extension and origin towards the app`() {
        val waiting = decodeFrame("""{"kind":"host","state":"waiting"}""".encodeToByteArray())
        val hello = decodeFrame(
            """{"kind":"host","origin":"chrome-extension://abc/","hostVersion":1}""".encodeToByteArray(),
        )

        assertEquals(BridgeFrame.Host(state = HostState.Waiting), waiting)
        assertEquals(BridgeFrame.Host(origin = "chrome-extension://abc/", hostVersion = 1), hello)
    }

    @Test
    fun `unknown fields are ignored for additive protocol changes`() {
        val frame = decodeFrame("""{"kind":"evt","event":"x","params":{},"future":42}""".encodeToByteArray())

        assertEquals(BridgeFrame.Event("x"), frame)
    }

    @Test
    fun `malformed payloads decode to null`() {
        assertNull(decodeFrame("""{"kind":"unknown"}""".encodeToByteArray()))
        assertNull(decodeFrame("""{"kind":"req","method":"x"}""".encodeToByteArray()))
        assertNull(decodeFrame("not json".encodeToByteArray()))
        assertNull(decodeFrame(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    @Test
    fun `unicode text survives the round trip`() {
        val frame = BridgeFrame.Event("chat.changed", buildJsonObject { put("text", "Привет 👋 \"quoted\"\n") })

        assertEquals(frame, decodeFrame(encodeFrame(frame)))
    }
}
