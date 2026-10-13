package io.aequicor.heartbeat.feature.chromiumbridge.impl.data

import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeFeatureSet
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeFrame
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeProtocol
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.HelloApp
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.HelloLimits
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.HelloParams
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.HelloResult
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.decodeFrame
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.HelloHandler
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.ExtensionIdentity
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.ChatChanged
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.ChatsChanged
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/** The extension and the app read the same files, so neither side drifts from the protocol alone. */
class ProtocolFixturesTest {
    private val root = Path.of(checkNotNull(System.getProperty("heartbeat.chromiumPlugin.root")))

    @Test
    fun `the manifest key pins the only allowed extension id`() {
        val manifest = Json.parseToJsonElement(Files.readString(root.resolve("manifest.json"))).jsonObject
        val key = Base64.getDecoder().decode(manifest.getValue("key").jsonPrimitive.content)
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        val id = digest.take(ID_BYTES).joinToString("") { byte ->
            val value = byte.toInt() and BYTE_MASK
            "${'a' + (value shr NIBBLE)}${'a' + (value and NIBBLE_MASK)}"
        }

        assertEquals(setOf(id), ExtensionIdentity.ids)
    }

    @Test
    fun `the extension's hello request decodes into the app's parameters`() {
        val request = decodeFrame(fixture("hello-request")) as BridgeFrame.Request

        val params = BridgeProtocol.json.decodeFromJsonElement(HelloParams.serializer(), request.params)

        assertEquals(HelloHandler.METHOD, request.method)
        assertEquals(listOf(BridgeProtocol.VERSION), params.protocol)
        assertEquals(listOf("chat"), params.capabilities)
    }

    @Test
    fun `the app's hello result matches what the extension expects`() {
        val response = decodeFrame(fixture("hello-result")) as BridgeFrame.Response

        val result = BridgeProtocol.json.decodeFromJsonElement(HelloResult.serializer(), checkNotNull(response.result))

        assertEquals(
            HelloResult(
                protocol = BridgeProtocol.VERSION,
                app = HelloApp("1.0.0"),
                features = BridgeFeatureSet(isChat = true, isAgentControl = false),
                limits = HelloLimits(BridgeProtocol.MAX_FROM_BROWSER_BYTES, HelloHandler.MAX_WATCHES),
            ),
            result,
        )
    }

    @Test
    fun `chat events the app sends match what the side panel reads`() {
        val list = decodeFrame(fixture("chats-changed")) as BridgeFrame.Event
        val transcript = decodeFrame(fixture("chat-changed")) as BridgeFrame.Event

        val chats = BridgeProtocol.json.decodeFromJsonElement(ChatsChanged.serializer(), list.params)
        val change = BridgeProtocol.json.decodeFromJsonElement(ChatChanged.serializer(), transcript.params)

        assertEquals(true, chats.chats.single().isRunning)
        assertEquals("text", chats.permissions.single().input?.kind)
        assertEquals(true, chats.permissions.single().options.last().isSkip)
        assertEquals(listOf("m0"), change.removed)
        assertEquals(true, change.upserts.single().parts?.last()?.isTruncated)
        assertEquals(
            list.params,
            BridgeProtocol.json.encodeToJsonElement(ChatsChanged.serializer(), chats),
        )
    }

    private fun fixture(name: String): ByteArray = Files.readAllBytes(
        root.resolve("tests/fixtures/protocol/$name.json"),
    )

    private companion object {
        const val ID_BYTES = 16
        const val BYTE_MASK = 0xFF
        const val NIBBLE = 4
        const val NIBBLE_MASK = 0x0F
    }
}
