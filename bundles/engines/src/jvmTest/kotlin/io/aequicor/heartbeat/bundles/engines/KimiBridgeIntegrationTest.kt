@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package io.aequicor.heartbeat.bundles.engines

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientHandler
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpImplementation
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionOutcome
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionRequest
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSessionUpdate
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpTransport
import io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.data.DefaultAcpConnection
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DesktopAgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.KimiRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KimiBridgeIntegrationTest {
    @Test
    @Suppress("LongMethod") // The sequence verifies one ACP-to-HTTP round trip and its replays.
    fun `ACP permission admits one call through the desktop MCP bridge`() = runBlocking {
        withTimeout(20_000) {
            val profile = TestProfile()
            val wire = TestWire()
            val tools = TestTools()
            val proxy = HandlerProxy()
            val connection = DefaultAcpConnection(wire, profile.coroutineScope, proxy)
            val init = async { connection.initialize(AcpImplementation("Heartbeat", "1")) }
            wire.reply(wire.next(), """{"protocolVersion":1}""")
            val initialization = init.await()
            val runtime = KimiRuntime(
                RuntimeIdentity(KimiEngine.Id, KimiEngine.AuthSource, AuthRevision.Known("test")),
                connection,
                profile.coroutineScope,
                initialization,
                { "/workspace" },
                TestToggles(),
                tools = tools,
                bridge = DesktopAgentToolBridge(tools, profile),
            )
            proxy.delegate = runtime
            try {
                val target = EngineTarget(KimiEngine.Id, EngineBindingId("binding"), ModelId("kimi-for-coding"))
                val creating = async { runtime.create(CreateSessionRequest(target, areDetachedToolsEnabled = true)) }
                val newSession = wire.next()
                assertEquals("session/new", newSession["method"]?.jsonPrimitive?.content)
                val server = newSession.getValue(
                    "params",
                ).jsonObject.getValue("mcpServers").jsonArray.single().jsonObject
                val url = server.getValue("url").jsonPrimitive.content
                val token = server.getValue(
                    "headers",
                ).jsonArray.single().jsonObject.getValue("value").jsonPrimitive.content
                assertTrue(mcpInitialize(url, token).contains("Instructions"))
                wire.reply(newSession, """{"sessionId":"native"}""")
                val session = creating.await()
                val sender = (session.features.resolve(SendsPrompts) as FeatureAccess.Available).feature
                val sending = async {
                    sender.send(
                        PromptRequest(RequestId("request"), listOf(ContentPart.Text("hello"))),
                    )
                }
                val prompt = wire.next()
                val turnToken = prompt.getValue("params").jsonObject.getValue("_meta").jsonObject
                    .getValue("heartbeatTurn").jsonPrimitive.content
                wire.input.send(
                    """{"jsonrpc":"2.0","id":99,"method":"session/request_permission","params":{""" +
                        """"sessionId":"native","toolCall":{"title":"mcp__heartbeat_tools__tool",""" +
                        """"_meta":{"heartbeatTurn":"$turnToken","heartbeatCall":"call-1"}},""" +
                        """"options":[{"optionId":"allow","name":"Allow","kind":"allow_once"}]}}""",
                )
                assertTrue(wire.next().toString().contains("selected"))
                sending.await()
                assertTrue(mcpCall(url, token, "forged").contains("\"isError\":true"))
                assertTrue(mcpCall(url, token, "call-1").contains("\"isError\":false"))
                assertTrue(mcpCall(url, token, "call-1").contains("\"isError\":true"))
                assertEquals(1, tools.calls.size)
                assertEquals(session.ref, tools.calls.single().session)
                assertEquals("x", tools.arguments.single().getValue("value").jsonPrimitive.content)
                assertTrue("__heartbeat_call" !in tools.arguments.single())
                wire.reply(prompt, """{"stopReason":"end_turn"}""")
            } finally {
                runtime.close()
                profile.close()
            }
        }
    }
}

private suspend fun mcpInitialize(url: String, token: String) = withContext(Dispatchers.IO) {
    val body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
    val request = HttpRequest.newBuilder(URI(url)).header("Authorization", token)
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body()
}

private suspend fun mcpCall(url: String, token: String, id: String) = withContext(Dispatchers.IO) {
    val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tool",""" +
        """"arguments":{"__heartbeat_call":"$id","value":"x"}}}"""
    val request = HttpRequest.newBuilder(URI(url)).header("Authorization", token)
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body()
}

private class HandlerProxy : AcpClientHandler {
    lateinit var delegate: AcpClientHandler
    override fun onPromptSubmitted(sessionId: String) = delegate.onPromptSubmitted(sessionId)
    override suspend fun onSessionUpdate(update: AcpSessionUpdate) = delegate.onSessionUpdate(update)
    override suspend fun requestPermission(request: AcpPermissionRequest): AcpPermissionOutcome =
        delegate.requestPermission(request)
}

private class TestWire : AcpTransport {
    val input = Channel<String>(Channel.UNLIMITED)
    private val output = Channel<String>(Channel.UNLIMITED)
    override suspend fun receive(): String? = input.receiveCatching().getOrNull()
    override suspend fun send(frame: String) {
        output.send(frame)
    }
    override fun close() {
        input.close()
        output.close()
    }
    suspend fun next(): JsonObject = Json.parseToJsonElement(output.receive()).jsonObject
    suspend fun reply(request: JsonObject, result: String) {
        input.send("""{"jsonrpc":"2.0","id":${request.getValue("id")},"result":$result}""")
    }
}

private class TestProfile :
    ScopeHandle,
    AutoCloseable {
    private val callbacks = mutableListOf<() -> Unit>()
    override val name = "test"
    override val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val savedState: ScopeSavedState get() = error("unused")
    override var isClosed = false
    override fun onClose(action: () -> Unit): DisposableHandle {
        callbacks += action
        return DisposableHandle { callbacks.remove(action) }
    }
    override fun close() {
        isClosed = true
        callbacks.toList().forEach { it() }
        coroutineScope.cancel()
    }
}

private class TestTools : ProfileAgentTools {
    val calls = mutableListOf<AgentToolContext>()
    val arguments = mutableListOf<JsonObject>()
    override suspend fun specifications(workspace: WorkspaceRef?) = listOf(
        AgentToolSpec("tool", "Tool", JsonObject(emptyMap())),
    )
    override suspend fun instructions(workspace: WorkspaceRef?) = "Instructions"
    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        calls += context
        this.arguments += arguments
        return AgentToolResult("ok")
    }
}

private class TestToggles : FeatureToggles {
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(value(toggle))
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = value(toggle)

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> value(toggle: FeatureToggle<T>): T = when (toggle) {
        is FeatureToggle.Flag -> true as T
        is FeatureToggle.Choice -> toggle.default
    }
}
