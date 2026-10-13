package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.chromiumbridge.api.BridgeServerStatus
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeAgentControl
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeEnabled
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeMachineKey
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bridge as the extension sees it: a client speaking the native host's framing on the app socket shakes hands,
 * checks studio availability and is shown in the bridge state; with agent control on, the `ex_control_*` tools exist.
 */
class ChromiumBridgeEndToEndTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val toggles = app as TestToggleAccessors
    private val isSupported = System.getProperty("os.name").orEmpty().let {
        it.startsWith("Mac", ignoreCase = true) || it.startsWith("Windows", ignoreCase = true)
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = runTest {
        app.closeAndAwaitStorages()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `an extension connects through the socket and checks studio availability`() = runTest {
        if (!isSupported) return@runTest
        toggles.toggleControl.setOverride(ChromiumBridgeEnabled, true)
        toggles.toggleControl.setOverride(ChromiumBridgeAgentControl, true)
        val profile = app.profileSessions.open(ProfileId("chromium-bridge-e2e")).graph as HostedToolCatalogAccessors
        val machine = bounded("bridge machine") { app.machines.observe(ChromiumBridgeMachineKey).first { it != null } }
        bounded("socket listening") {
            machine.state.first { it is ChromiumBridgeState.Enabled && it.server == BridgeServerStatus.Listening }
        }

        connect().use { extension ->
            extension.write("""{"kind":"host","origin":"$ORIGIN","hostVersion":1}""")
            extension.write(HELLO)
            val hello = bounded("hello") { extension.response("e:1") }
            extension.write(
                """{"kind":"req","id":"e:2","method":"studio.watch","params":{"view":"test","schema":"unused"}}""",
            )
            val watch = bounded("studio.watch") { extension.response("e:2") }

            val result = hello.getValue("result").jsonObject
            assertEquals(1, result.getValue("protocol").jsonPrimitive.int)
            // Studio views are off by default: the panel is told so instead of getting an empty studio.
            assertEquals(false, result.getValue("features").jsonObject.getValue("studio").jsonPrimitive.boolean)
            assertEquals(true, result.getValue("features").jsonObject.getValue("agentControl").jsonPrimitive.boolean)
            assertEquals("unavailable", watch.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            val connected = bounded("connection shown") {
                machine.state.first { (it as? ChromiumBridgeState.Enabled)?.connections?.isNotEmpty() == true }
            } as ChromiumBridgeState.Enabled
            assertEquals("Google Chrome", connected.connections.single().brand)
        }
        bounded("connection gone") {
            machine.state.first { (it as? ChromiumBridgeState.Enabled)?.connections?.isEmpty() == true }
        }
        val tools = profile.toolContributions.single { it.group == "external_browser" }
        val names = tools.specifications(AgentToolScope(null)).map { it.name }
        assertTrue(names.isNotEmpty() && names.all { it.startsWith("ex_control_") }, "Declared: $names")
        app.profileSessions.close()
    }

    @Test
    fun `a foreign extension is turned away`() = runTest {
        if (!isSupported) return@runTest
        toggles.toggleControl.setOverride(ChromiumBridgeEnabled, true)
        app.profileSessions.open(ProfileId("chromium-bridge-foreign"))
        val machine = bounded("bridge machine") { app.machines.observe(ChromiumBridgeMachineKey).first { it != null } }
        bounded("socket listening") {
            machine.state.first { it is ChromiumBridgeState.Enabled && it.server == BridgeServerStatus.Listening }
        }

        connect().use { foreign ->
            foreign.write("""{"kind":"host","origin":"chrome-extension://${"a".repeat(32)}/","hostVersion":1}""")
            foreign.write(HELLO)

            assertTrue(bounded("closed") { foreign.awaitEnd() })
        }
        app.profileSessions.close()
    }

    /** Mirrors `BridgePaths.socket`: long data paths fall back to a digest-named temporary directory. */
    private fun socketPath(): Path {
        val preferred = Path.of(app.appStores.filesDirectory("chromium_bridge")).toAbsolutePath()
            .resolve("run").resolve("bridge.sock").normalize()
        if (preferred.toString().toByteArray().size <= MAX_SOCKET_PATH_BYTES) return preferred
        val digest = MessageDigest.getInstance("SHA-256").digest(preferred.toString().toByteArray())
            .let(HexFormat.of()::formatHex)
        return Path.of(System.getProperty("java.io.tmpdir")).resolve("heartbeat-${digest.take(12)}")
            .resolve("bridge.sock").toAbsolutePath().normalize()
    }

    private fun connect() = FakeExtension(
        SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(UnixDomainSocketAddress.of(socketPath())) },
    )

    private suspend fun <T : Any> bounded(checkpoint: String, action: suspend () -> T?): T =
        withContext(app.dispatchers.default) {
            try {
                withTimeout(TIMEOUT_MILLIS) { action() } ?: throw AssertionError("Timed out at $checkpoint")
            } catch (e: TimeoutCancellationException) {
                throw AssertionError("Timed out at $checkpoint", e)
            }
        }

    /** The extension side of the native host: little-endian length-prefixed JSON frames. */
    private class FakeExtension(private val channel: SocketChannel) : AutoCloseable {
        fun write(json: String) {
            val payload = json.encodeToByteArray()
            val frame = ByteBuffer.allocate(Int.SIZE_BYTES + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            frame.putInt(payload.size).put(payload).flip()
            while (frame.hasRemaining()) channel.write(frame)
        }

        suspend fun response(id: String): JsonObject = next {
            it["kind"]?.jsonPrimitive?.content == "res" && it["id"]?.jsonPrimitive?.content == id
        }

        /** The next frame, or `null` once the app closed the connection. */
        suspend fun readOrEnd(): JsonObject? = runInterruptible(Dispatchers.IO) {
            val header = ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            if (!fill(header)) return@runInterruptible null
            val payload = ByteBuffer.allocate(header.flip().int)
            if (!fill(payload)) return@runInterruptible null
            Json.parseToJsonElement(payload.array().decodeToString()).jsonObject
        }

        /** Reads until the app closes the connection. */
        suspend fun awaitEnd(): Boolean {
            while (readOrEnd() != null) continue
            return true
        }

        private suspend fun next(matches: (JsonObject) -> Boolean): JsonObject {
            while (true) {
                val frame = readOrEnd() ?: throw AssertionError("The app closed the connection")
                if (matches(frame)) return frame
            }
        }

        private fun fill(buffer: ByteBuffer): Boolean {
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) return false
            return true
        }

        override fun close() = channel.close()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
        const val MAX_SOCKET_PATH_BYTES = 100
        const val ORIGIN = "chrome-extension://dngnkjkjppehmiaebojfebjcfkcpdjki/"
        const val HELLO = """{"kind":"req","id":"e:1","method":"hello","params":{"protocol":[1],""" +
            """"extension":{"version":"0.1.0"},"browser":{"brand":"Google Chrome","version":"140"},""" +
            """"capabilities":["studio"]}}"""
    }
}
