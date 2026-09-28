package io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientHandler
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpImplementation
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionOutcome
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionRequest
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSessionUpdate
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpTransport
import io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.di.DefaultAcpClientFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertEquals

internal class AcpTestHarness(val test: TestScope) {
    val transport = FakeTransport()
    val handler = RecordingHandler()
    val client = DefaultAcpClientFactory(TestDispatchers(StandardTestDispatcher(test.testScheduler)))
        .connect(transport, test.backgroundScope, handler)

    suspend fun initialize(capabilities: String = "{}") {
        val task = test.backgroundScope.async { client.initialize(AcpImplementation("test", "1")) }
        val request = outgoing("initialize")
        reply(
            request,
            (
                """{"protocolVersion":1,"agentCapabilities":$capabilities,""" +
""""authMethods":[{"id":"login","name":"Login"}]}"""
            ),
        )
        task.await()
    }

    suspend fun session(id: String = "session") {
        val task = test.backgroundScope.async { client.newSession("/workspace") }
        reply(outgoing("session/new"), """{"sessionId":"$id"}""")
        assertEquals(id, task.await().sessionId)
    }

    suspend fun outgoing(method: String): JsonObject {
        val result = acpJson.parseToJsonElement(transport.output.receive()) as JsonObject
        assertEquals(method, result.string("method"))
        assertEquals("2.0", result.string("jsonrpc"))
        return result
    }

    suspend fun reply(request: JsonObject, result: String) {
        transport.input.send("""{"jsonrpc":"2.0","id":${request["id"]},"result":$result}""")
    }

    suspend fun notification(
        update: String = """{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"hello"}}""",
    ) {
        transport.input.send(
            """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"session","update":$update}}""",
        )
    }

    suspend fun permission(id: Int = 90) {
        transport.input.send(
            (
                """{"jsonrpc":"2.0","id":$id,"method":"session/request_permission",""" +
""""params":{"sessionId":"session","toolCall":{"toolCallId":"tool"},""" +
""""options":[{"optionId":"allow","name":"Allow","kind":"allow_once"}]}}"""
            ),
        )
    }
}

internal class FakeTransport : AcpTransport {
    val input = Channel<String>(Channel.UNLIMITED)
    val output = Channel<String>(Channel.UNLIMITED)
    var isClosed = false
    var beforeSend: suspend (String) -> Unit = {}

    override suspend fun receive(): String? = input.receiveCatching().getOrNull()

    override suspend fun send(frame: String) {
        check(!isClosed)
        beforeSend(frame)
        output.send(frame)
    }

    override fun close() {
        isClosed = true
        input.close()
    }
}

internal class RecordingHandler : AcpClientHandler {
    val updates = mutableListOf<AcpSessionUpdate>()
    var permission: suspend (AcpPermissionRequest) -> AcpPermissionOutcome = { AcpPermissionOutcome.Cancelled }

    override suspend fun onSessionUpdate(update: AcpSessionUpdate) {
        updates.add(update)
    }

    override suspend fun requestPermission(request: AcpPermissionRequest): AcpPermissionOutcome = permission(request)
}

internal class TestDispatchers(private val dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main = dispatcher
    override val default = dispatcher
    override val io = dispatcher
}

internal fun JsonObject.id(): JsonPrimitive = getValue("id") as JsonPrimitive
