package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolImage
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopAgentToolBridgeTest {
    @Test
    fun `MCP handshake uses stored session scope and calls cannot substitute another session`() = runBlocking {
        val profile = BridgeProfile()
        val captured = mutableListOf<AgentToolScope>()
        val original = context()
        var active = original
        var calls = 0
        val tools = object : ProfileAgentTools {
            override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> = error("Use scope")
            override suspend fun instructions(workspace: WorkspaceRef?): String = error("Use scope")
            override suspend fun specifications(scope: AgentToolScope): List<AgentToolSpec> {
                captured += scope
                return emptyList()
            }
            override suspend fun instructions(scope: AgentToolScope): String {
                captured += scope
                return "Scoped instructions"
            }
            override suspend fun execute(
                context: AgentToolContext,
                name: String,
                arguments: JsonObject,
            ): AgentToolResult {
                calls++
                return AgentToolResult("done")
            }
        }
        val scope = AgentToolScope(PROJECT, session = original.session)
        val capability = DesktopAgentToolBridge(tools, profile).attach(scope) { active }
        try {
            val endpoint = capability.endpoint
            assertTrue(post(endpoint.url, LIST, endpoint.token).body().contains("\"tools\":[]"))
            val initialize = """{"jsonrpc":"2.0","id":3,"method":"initialize"}"""
            assertTrue(post(endpoint.url, initialize, endpoint.token).body().contains("Scoped instructions"))
            assertEquals(listOf(scope, scope), captured)
            active = original.copy(session = original.session.copy(nativeId = "foreign"))
            assertTrue(post(endpoint.url, CALL, endpoint.token).body().contains("\"isError\":true"))
            assertEquals(0, calls)
            active = original
            assertTrue(post(endpoint.url, CALL, endpoint.token).body().contains("\"isError\":false"))
            assertEquals(1, calls)
        } finally {
            capability.close()
            profile.close()
        }
    }

    @Test
    fun `execute and MCP deliver image content without truncating it to the text limit`() = runBlocking {
        val profile = BridgeProfile()
        val image = AgentToolImage("image/jpeg", "AQID".repeat(100_000))
        val tools = CapturingTools(AgentToolResult("geometry", images = listOf(image)))
        val capability = DesktopAgentToolBridge(tools, profile).attach(PROJECT) { context() }
        try {
            val endpoint = capability.endpoint
            val mcp = Json.parseToJsonElement(post(endpoint.url, CALL, endpoint.token).body()).jsonObject
            val content = mcp.getValue("result").jsonObject.getValue("content") as JsonArray
            assertEquals("geometry", content[0].jsonObject.getValue("text").jsonPrimitive.content)
            assertEquals("image", content[1].jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(image.mimeType, content[1].jsonObject.getValue("mimeType").jsonPrimitive.content)
            assertEquals(image.data, content[1].jsonObject.getValue("data").jsonPrimitive.content)
            val response = HttpClient.newHttpClient().send(
                request(endpoint.url, """{"name":"tool","arguments":{}}""", endpoint.token, "/execute"),
                HttpResponse.BodyHandlers.ofString(),
            )
            val output = Json.parseToJsonElement(response.body()).jsonObject
            val images = output.getValue("images") as JsonArray
            assertEquals(content[1], images.single())
            assertEquals(2, tools.calls.size)
        } finally {
            capability.close()
            profile.close()
        }
    }

    @Test
    fun `queued invocation keeps its ingress turn and cannot inherit the next turns full trust`() = runBlocking {
        val dispatcher = QueuedBridgeDispatcher()
        val profile = BridgeProfile(dispatcher)
        val tools = CapturingTools()
        val lifetime = Job()
        var active = context().copy(lifetime = lifetime)
        val capability = DesktopAgentToolBridge(tools, profile).attach(PROJECT) { active }
        try {
            val response = HttpClient.newHttpClient().sendAsync(
                request(capability.endpoint.url, CALL, capability.endpoint.token),
                HttpResponse.BodyHandlers.ofString(),
            )
            val queued = dispatcher.next()
            active = context().copy(turn = TurnId("next"), trust = TrustLevel.Full, lifetime = Job())
            queued.run()
            assertEquals(200, response.get(5, TimeUnit.SECONDS).statusCode())
            assertEquals(TurnId("turn"), tools.calls.single().turn)
            assertEquals(TrustLevel.Ask, tools.calls.single().trust)
        } finally {
            lifetime.cancel()
            active.lifetime?.cancel()
            capability.close()
            profile.close()
        }
    }

    @Test
    fun `turn revocation rejects a queued call even when the session capability has a new turn`() = runBlocking {
        val dispatcher = QueuedBridgeDispatcher()
        val profile = BridgeProfile(dispatcher)
        val tools = CapturingTools()
        val lifetime = Job()
        var active = context().copy(lifetime = lifetime)
        val capability = DesktopAgentToolBridge(tools, profile).attach(PROJECT) { active }
        try {
            val response = HttpClient.newHttpClient().sendAsync(
                request(capability.endpoint.url, CALL, capability.endpoint.token),
                HttpResponse.BodyHandlers.ofString(),
            )
            val queued = dispatcher.next()
            lifetime.cancel()
            active = context().copy(turn = TurnId("next"), trust = TrustLevel.Full, lifetime = Job())
            queued.run()
            assertEquals(410, response.get(5, TimeUnit.SECONDS).statusCode())
            assertTrue(tools.calls.isEmpty())
        } finally {
            active.lifetime?.cancel()
            capability.close()
            profile.close()
        }
    }

    @Test
    fun `revocation closes a request whose execution has not started`() = runBlocking {
        val dispatcher = QueuedBridgeDispatcher()
        val profile = BridgeProfile(dispatcher)
        val tools = CapturingTools()
        val bridge = DesktopAgentToolBridge(tools, profile)
        val capability = bridge.attach(PROJECT) { context() }
        try {
            val response = HttpClient.newHttpClient().sendAsync(
                request(capability.endpoint.url, CALL, capability.endpoint.token),
                HttpResponse.BodyHandlers.ofString(),
            )
            val execution = dispatcher.next()
            capability.close()
            execution.run()
            assertEquals(410, response.get(5, TimeUnit.SECONDS).statusCode())
            assertTrue(tools.calls.isEmpty())
        } finally {
            capability.close()
            profile.close()
        }
    }

    @Test
    fun `MCP authenticates handshake separately from active turn and revokes capabilities`() = runBlocking {
        val profile = BridgeProfile()
        val tools = CapturingTools()
        val bridge = DesktopAgentToolBridge(tools, profile)
        var active: AgentToolContext? = null
        var contexts = 0
        val capability = bridge.attach(PROJECT) {
            contexts++
            active
        }
        val endpoint = capability.endpoint
        try {
            assertEquals(403, post(endpoint.url, "{}", "wrong").statusCode())
            val listed = post(endpoint.url, LIST, endpoint.token)
            assertEquals(200, listed.statusCode())
            assertTrue(listed.body().contains("tool"))
            assertEquals(0, contexts)
            val idle = post(endpoint.url, CALL, endpoint.token)
            assertTrue(idle.body().contains("\"isError\":true"))
            assertEquals(0, tools.calls.size)
            active = context()
            val result = post(endpoint.url, CALL, endpoint.token)
            assertTrue(result.body().contains("\"isError\":false"))
            val trusted = tools.calls.single()
            assertEquals(active.session, trusted.session)
            assertEquals(PROJECT, trusted.workspace)
            assertEquals(TurnId("turn"), trusted.turn)
            assertEquals("mcp-2", trusted.callId?.value)
            active = context().copy(workspace = WorkspaceRef("foreign"))
            assertTrue(post(endpoint.url, CALL, endpoint.token).body().contains("\"isError\":true"))
            assertEquals(1, tools.calls.size)
            capability.close()
            assertEquals(403, post(endpoint.url, CALL, endpoint.token).statusCode())
        } finally {
            capability.close()
            profile.close()
        }
    }

    @Test
    fun `revoking a capability cancels pending authorization independently of the native job`() = runBlocking {
        val profile = BridgeProfile()
        val lifetime = Job()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        var executions = 0
        val owner = object : AgentToolContribution {
            override suspend fun specifications(workspace: WorkspaceRef?) = listOf(
                AgentToolSpec("tool", "Run command", JsonObject(emptyMap()), AgentToolAction.Command),
            )
            override suspend fun execute(
                context: AgentToolContext,
                name: String,
                arguments: JsonObject,
            ): AgentToolResult {
                executions++
                return AgentToolResult("done")
            }
        }
        val bridge = DesktopAgentToolBridge(DefaultAgentTools(setOf(owner)), profile)
        val active = context().copy(
            lifetime = lifetime,
            permissions = AgentToolPermissions {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                    Unit
                }
            },
        )
        val capability = bridge.attach(PROJECT) { active }
        try {
            val request = HttpClient.newHttpClient().sendAsync(
                request(capability.endpoint.url, CALL, capability.endpoint.token),
                HttpResponse.BodyHandlers.ofString(),
            )
            withTimeout(5000) { entered.await() }
            capability.close()
            withTimeout(5000) { stopped.await() }
            assertEquals(410, request.get(5, TimeUnit.SECONDS).statusCode())
            assertTrue(lifetime.isActive)
            assertEquals(0, executions)
        } finally {
            lifetime.cancel()
            capability.close()
            profile.close()
        }
    }
}

private class CapturingTools(private val result: AgentToolResult = AgentToolResult("done")) : ProfileAgentTools {
    val calls = mutableListOf<AgentToolContext>()
    override suspend fun specifications(workspace: WorkspaceRef?) = listOf(
        AgentToolSpec("tool", "Tool", JsonObject(emptyMap())),
    )
    override suspend fun instructions(workspace: WorkspaceRef?) = "Host instructions"
    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        calls += context
        return result
    }
}

private class BridgeProfile(
    private val dispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher(),
) : ScopeHandle,
    AutoCloseable {
    private val callbacks = mutableListOf<() -> Unit>()
    override val name = "bridge-test"
    override val coroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
    override val savedState: ScopeSavedState get() = error("Not needed")
    override var isClosed = false
        private set
    override fun onClose(action: () -> Unit): DisposableHandle {
        callbacks += action
        return DisposableHandle { callbacks.remove(action) }
    }
    override fun close() {
        if (isClosed) return
        isClosed = true
        callbacks.toList().forEach { it() }
        coroutineScope.cancel()
        (dispatcher as? ExecutorCoroutineDispatcher)?.close()
    }
}

private class QueuedBridgeDispatcher : CoroutineDispatcher() {
    private val executions = LinkedBlockingQueue<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        executions.add(block)
    }
    fun next(): Runnable = checkNotNull(executions.poll(5, TimeUnit.SECONDS)) { "No bridge execution was queued" }
}

private fun context() = AgentToolContext(
    SessionRef(EngineId("test"), SessionSourceId("local"), "native"),
    PROJECT,
    TurnId("turn"),
)

private fun request(origin: String, body: String, token: String, path: String = "/mcp"): HttpRequest =
    HttpRequest.newBuilder(URI("$origin$path")).timeout(Duration.ofSeconds(10))
        .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build()

private fun post(origin: String, body: String, token: String) =
    HttpClient.newHttpClient().send(request(origin, body, token), HttpResponse.BodyHandlers.ofString())

private val PROJECT = WorkspaceRef("project")
private const val LIST = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
private const val CALL = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"tool","arguments":{}}}"""
