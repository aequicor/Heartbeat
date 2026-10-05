package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeAttachment
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeEndpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnavailableAgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridgeEndpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClaudeHostedTest {
    private val workspace = WorkspaceRef("local-project")

    @Test
    fun `hosted acceptance persists image originals and restart resumes without reading the file`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.inputSupport = fixture.inputSupport.copy(imageMediaTypes = setOf("image/png"))
        fixture.resources = ResourceResolver { ResolvedResource("image.png", "image/png", byteArrayOf(1)) }
        val bridge = TestAgentBridge()
        val finish = CompletableDeferred<Unit>()
        fixture.transport.generation = { args, line ->
            assertNotNull(bridge.context())
            finish.await()
            val id = args.first { it.startsWith("--session-id=") }.substringAfter('=')
            line(resultFrame(id))
            0
        }
        val first = fixture.runtime(TestAgentTools(), bridge)
        val session = first.create(CreateSessionRequest(testTarget, workspace))
        val parts = listOf(ContentPart.Image(ResourceRef("attachment:image", "image/png")))
        val send = async { session.features.available(SendsPrompts).send(PromptRequest(RequestId("image"), parts)) }
        runCurrent()
        val turn = send.await()
        val saved = assertNotNull(fixture.catalog.find(session.ref))
        val original = saved.history.items.filterIsInstance<SessionItem.Message>().single()
        assertEquals(parts, original.parts)
        assertEquals(turn, original.info.turn)
        assertTrue("--input-format" in fixture.transport.calls.last())
        session.close()
        finish.complete(Unit)
        runCurrent()
        first.close()
        fixture.resources = ResourceResolver { error("Restoring history must not load original bytes") }
        val second = fixture.runtime(TestAgentTools(), TestAgentBridge())
        val restored = second.attach(session.ref, ResumeSessionRequest(testTarget, workspace))
        val user = restored.features.available(SessionHistory).page().items
            .filterIsInstance<SessionItem.Message>().single { it.role == MessageRole.User }
        assertEquals(parts, user.parts)
        assertEquals(session.ref, restored.ref)
        fixture.transport.generation = { args, line ->
            val id = args.first { it.startsWith("--resume=") }.substringAfter('=')
            line(initFrame(id))
            line(
                """{"type":"assistant","session_id":"$id","message":{"content":[
                {"type":"tool_use","id":"child","name":"Agent","input":{"prompt":"describe"}}]}}""",
            )
            line(
                """{"type":"user","session_id":"$id","parent_tool_use_id":"child","message":{"content":[
                {"type":"image","source":{"data":"AQ==","media_type":"image/png","type":"base64"}}]}}""",
            )
            line(resultFrame(id))
            0
        }
        val resumed = async { restored.features.available(SendsPrompts).send(prompt("after-restart")) }
        runCurrent()
        resumed.await()
        runCurrent()
        val child = assertNotNull(fixture.catalog.find(session.ref)).children.single()
        val childImage = child.history.items.filterIsInstance<SessionItem.Message>().last()
        assertEquals(parts, childImage.parts)
        second.close()
    }

    @Test
    fun `session without a project gets hosted tools only when its caller opted in`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val bridge = TestAgentBridge()
        val contexts = mutableListOf<AgentToolContext?>()
        fixture.transport.generation = { args, line ->
            contexts += bridge.context()
            line(resultFrame(args.first { it.startsWith("--session-id=") }.substringAfter('=')))
            0
        }
        val runtime = fixture.runtime(TestAgentTools(), bridge)
        val plain = runtime.create(CreateSessionRequest(testTarget))
        plain.features.available(SendsPrompts).send(prompt("plain"))
        runCurrent()
        assertTrue(bridge.attached.isEmpty())
        assertNull(fixture.transport.hostedCalls.last())

        val chat = runtime.create(CreateSessionRequest(testTarget, areDetachedToolsEnabled = true))
        val turn = chat.features.available(SendsPrompts).send(prompt("chat"))
        runCurrent()
        assertEquals(listOf<WorkspaceRef?>(null), bridge.attached)
        val hosted = assertNotNull(fixture.transport.hostedCalls.last())
        assertFalse(hosted.isProject)
        val context = assertNotNull(contexts.last())
        assertNull(context.workspace)
        assertEquals(turn, context.turn)
        assertEquals(testTarget, context.target)
        runtime.close()
    }

    @Test
    fun `chat reopened without holders takes the opt in of the resuming caller`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val bridge = TestAgentBridge()
        val contexts = mutableListOf<AgentToolContext?>()
        fixture.transport.generation = { args, line ->
            contexts += bridge.context()
            line(resultFrame(nativeId(args)))
            0
        }
        val runtime = fixture.runtime(TestAgentTools(), bridge)
        val created = runtime.create(CreateSessionRequest(testTarget))
        created.features.available(SendsPrompts).send(prompt("plain"))
        runCurrent()
        assertTrue(bridge.attached.isEmpty())
        created.close()

        val chat = runtime.stored(created.ref).features.available(ResumesSessions)
            .resume(ResumeSessionRequest(testTarget, areDetachedToolsEnabled = true))
        val other = runtime.attach(created.ref, ResumeSessionRequest(testTarget))
        val turn = other.features.available(SendsPrompts).send(prompt("chat"))
        runCurrent()
        assertEquals(listOf<WorkspaceRef?>(null), bridge.attached)
        val context = assertNotNull(contexts.last())
        assertNull(context.workspace)
        assertEquals(turn, context.turn)
        chat.close()
        other.close()
        runtime.close()
    }

    @Test
    fun `accepted turn keeps the opt in of its sender when a new holder attaches before it starts`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val bridge = TestAgentBridge()
        fixture.transport.generation = { args, line ->
            line(resultFrame(nativeId(args)))
            0
        }
        val runtime = fixture.runtime(TestAgentTools(), bridge)
        val created = runtime.create(CreateSessionRequest(testTarget))
        val send = async { created.features.available(SendsPrompts).send(prompt("plain")) }
        // Accepted under the session lock; its process has not started yet.
        while (created.state.value !is ActiveSessionState.Submitting) yield()
        created.close()
        val chat = runtime.attach(created.ref, ResumeSessionRequest(testTarget, areDetachedToolsEnabled = true))
        send.await()
        runCurrent()
        assertTrue(bridge.attached.isEmpty())
        assertNull(fixture.transport.hostedCalls.last())

        chat.features.available(SendsPrompts).send(prompt("chat"))
        runCurrent()
        assertEquals(listOf<WorkspaceRef?>(null), bridge.attached)
        chat.close()
        runtime.close()
    }

    @Test
    fun `chat restored after restart takes the opt in of the resuming caller`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val bridge = TestAgentBridge()
        fixture.transport.generation = { args, line ->
            line(resultFrame(nativeId(args)))
            0
        }
        val first = fixture.runtime(TestAgentTools(), bridge)
        val created = first.create(CreateSessionRequest(testTarget, areDetachedToolsEnabled = true))
        first.close()
        val second = fixture.runtime(TestAgentTools(), bridge)
        val chat = second.stored(created.ref).features.available(ResumesSessions)
            .resume(ResumeSessionRequest(testTarget, areDetachedToolsEnabled = true))
        chat.features.available(SendsPrompts).send(prompt("chat"))
        runCurrent()
        assertEquals(listOf<WorkspaceRef?>(null), bridge.attached)
        assertFalse(assertNotNull(fixture.transport.hostedCalls.last()).isProject)
        second.close()
    }

    @Test
    fun `chat without a project answers when its hosted tools fail`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val failing = object : AgentToolBridge {
            override val isAvailable = true
            override suspend fun attach(
                workspace: WorkspaceRef?,
                context: suspend () -> AgentToolContext?,
            ): AgentToolBridgeAttachment = error("Bridge failed")
        }
        val attached = TestAgentBridge()
        val failingInstructions = object : ProfileAgentTools by TestAgentTools() {
            override suspend fun instructions(scope: AgentToolScope): String = error("Contribution failed")
        }
        listOf(
            TestAgentTools() to failing,
            TestAgentTools() to UnavailableAgentToolBridge,
            failingInstructions to attached,
        ).forEach { (tools, bridge) ->
            val runtime = fixture.runtime(tools, bridge)
            val chat = runtime.create(CreateSessionRequest(testTarget, areDetachedToolsEnabled = true))
            chat.features.available(SendsPrompts).send(prompt())
            runCurrent()
            assertNull(fixture.transport.hostedCalls.last())
            assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(chat.state.value).lastTurn?.outcome)
            runtime.close()
        }
        assertEquals(listOf<WorkspaceRef?>(null), attached.attached)
        assertEquals(1, attached.closed)
    }

    @Test
    fun `detached hosted arguments keep provider web search`() {
        val config = Files.createTempFile("heartbeat-mcp-", ".json")
        try {
            val args = claudeHostedArguments(
                claudeArguments(search = true),
                config,
                config.resolveSibling("prompt.txt"),
                search = true,
                isProviderSearchKept = true,
            )
            assertFalse("--tools=" in args)
            assertTrue("--tools=WebSearch" in args)
            assertTrue("--allowedTools=mcp__heartbeat_tools__*,mcp__heartbeat_search__*,WebSearch" in args)
            assertTrue("--strict-mcp-config" in args)
        } finally {
            Files.deleteIfExists(config)
        }
    }

    @Test
    fun `MCP call proves acceptance before buffered stdout and permissions belong to that turn`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val bridge = TestAgentBridge()
        val finish = CompletableDeferred<Unit>()
        fixture.transport.generation = { args, line ->
            assertNotNull(bridge.context())
            finish.await()
            line(resultFrame(args.last().substringAfter('=')))
            0
        }
        val runtime = fixture.runtime(TestAgentTools(), bridge)
        val session = runtime.create(CreateSessionRequest(testTarget, workspace))
        val send = async { session.features.available(SendsPrompts).send(prompt().copy(trust = TrustLevel.AutoEdits)) }
        runCurrent()
        val turn = send.await()
        val context = assertNotNull(bridge.context())
        assertEquals(session.ref, context.session)
        assertEquals(turn, context.turn)
        assertEquals(workspace, context.workspace)
        assertEquals(TrustLevel.AutoEdits, context.trust)
        assertTrue(assertNotNull(context.lifetime).isActive)
        val decision = async { context.permissions.request(AgentToolApproval("run_command", "Run build?")) }
        runCurrent()
        val waiting = assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value)
        val request = waiting.requests.single()
        val approvals = session.features.available(RequestsPermissions)
        approvals.respond(PermissionDecision(turn, PermissionRequestId("stale"), request.options.first().id))
        assertFalse(decision.isCompleted)
        approvals.respond(PermissionDecision(turn, request.id, request.options.first().id))
        runCurrent()
        assertTrue(decision.await())
        assertTrue(request.id in assertIs<ActiveSessionState.Running>(session.state.value).turn.resolvedPermissions)
        finish.complete(Unit)
        runCurrent()
        assertNull(bridge.context())
        assertEquals(1, bridge.closed)
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
        runtime.close()
    }

    @Test
    fun `last lease detach declines pending and later Ask requests without revoking accepted work`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val bridge = TestAgentBridge()
        val finish = CompletableDeferred<Unit>()
        fixture.transport.generation = { args, line ->
            assertNotNull(bridge.context())
            finish.await()
            line(resultFrame(args.last().substringAfter('=')))
            0
        }
        val runtime = fixture.runtime(TestAgentTools(), bridge)
        val session = runtime.create(CreateSessionRequest(testTarget, workspace))
        val send = async { session.features.available(SendsPrompts).send(prompt()) }
        runCurrent()
        send.await()
        val context = assertNotNull(bridge.context())
        val answer = async { context.permissions.request(AgentToolApproval("run_command", "Command")) }
        runCurrent()
        assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value)
        session.close()
        runCurrent()
        assertFalse(answer.await())
        val detachedContext = assertNotNull(bridge.context())
        assertTrue(assertNotNull(detachedContext.lifetime).isActive)
        assertFalse(detachedContext.permissions.request(AgentToolApproval("run_command", "Later command")))
        assertEquals(0, bridge.closed)
        finish.complete(Unit)
        runCurrent()
        assertEquals(TurnOutcome.Completed, fixture.catalog.find(session.ref)?.lastTurn?.outcome)
        runtime.close()
    }

    @Test
    fun `closing one of two leases keeps the other able to approve`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val bridge = TestAgentBridge()
        val finish = CompletableDeferred<Unit>()
        fixture.transport.generation = { args, line ->
            assertNotNull(bridge.context())
            finish.await()
            line(resultFrame(args.last().substringAfter('=')))
            0
        }
        val runtime = fixture.runtime(TestAgentTools(), bridge)
        val session = runtime.create(CreateSessionRequest(testTarget, workspace))
        val other = runtime.attach(session.ref, ResumeSessionRequest(testTarget, workspace))
        val send = async { session.features.available(SendsPrompts).send(prompt()) }
        runCurrent()
        val turn = send.await()
        val context = assertNotNull(bridge.context())
        val answer = async { context.permissions.request(AgentToolApproval("run_command", "Command")) }
        runCurrent()
        session.close()
        runCurrent()
        assertFalse(answer.isCompleted)
        val request = assertIs<ActiveSessionState.AwaitingUserAction>(other.state.value).requests.single()
        other.features.available(RequestsPermissions)
            .respond(PermissionDecision(turn, request.id, request.options.first().id))
        runCurrent()
        assertTrue(answer.await())
        finish.complete(Unit)
        runCurrent()
        runtime.close()
    }

    @Test
    fun `Full trusted turn retains its tool capability after the last lease detaches`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val bridge = TestAgentBridge()
        val finish = CompletableDeferred<Unit>()
        fixture.transport.generation = { args, line ->
            assertNotNull(bridge.context())
            finish.await()
            line(resultFrame(args.last().substringAfter('=')))
            0
        }
        val runtime = fixture.runtime(TestAgentTools(), bridge)
        val session = runtime.create(CreateSessionRequest(testTarget, workspace))
        val send = async { session.features.available(SendsPrompts).send(prompt().copy(trust = TrustLevel.Full)) }
        runCurrent()
        send.await()
        session.close()
        val context = assertNotNull(bridge.context())
        assertEquals(TrustLevel.Full, context.trust)
        assertTrue(assertNotNull(context.lifetime).isActive)
        finish.complete(Unit)
        runCurrent()
        assertEquals(TurnOutcome.Completed, fixture.catalog.find(session.ref)?.lastTurn?.outcome)
        runtime.close()
    }

    @Test
    fun `cancel waits for native cleanup and revokes a pending permission`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val bridge = TestAgentBridge()
        var isCleaned = false
        fixture.transport.generation = { args, line ->
            val id = args.last().substringAfter('=')
            line(initFrame(id))
            line(assistantFrame(id))
            line(
                """{"type":"assistant","session_id":"$id","message":{"content":[
                {"type":"tool_use","id":"child","name":"Agent",
                "input":{"prompt":"work","run_in_background":true}}]}}""",
            )
            try {
                awaitCancellation()
            } finally {
                isCleaned = true
            }
        }
        val runtime = fixture.runtime(TestAgentTools(), bridge)
        val session = runtime.create(CreateSessionRequest(testTarget, workspace))
        val send = async { session.features.available(SendsPrompts).send(prompt()) }
        runCurrent()
        val turn = send.await()
        val context = assertNotNull(bridge.context())
        val approval = async { context.permissions.request(AgentToolApproval("run_command", "Command")) }
        runCurrent()
        session.features.available(CancelsTurns).cancel(turn)
        assertTrue(isCleaned)
        assertEquals("Cancelled", fixture.catalog.find(session.ref)?.children?.single()?.activity)
        assertFalse(approval.await())
        assertNull(bridge.context())
        assertEquals(TurnOutcome.Cancelled, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
        assertEquals(TurnOutcome.Cancelled, fixture.catalog.find(session.ref)?.lastTurn?.outcome)
        runtime.close()
    }

    @Test
    fun `restart restores history checkpoints and resumes the same native UUID`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val first = fixture.runtime()
        val session = first.create(CreateSessionRequest(testTarget, workspace))
        val sent = async { session.features.available(SendsPrompts).send(prompt()) }
        runCurrent()
        sent.await()
        val page = session.features.available(SessionHistory).page()
        first.close()
        val second = fixture.runtime()
        val resumed = second.attach(session.ref, ResumeSessionRequest(testTarget, workspace))
        val restored = resumed.features.available(SessionHistory).page()
        assertEquals(page, restored)
        val sentAgain = async { resumed.features.available(SendsPrompts).send(prompt("next")) }
        runCurrent()
        sentAgain.await()
        assertTrue("--resume=${session.ref.nativeId}" in fixture.transport.calls.last())
        assertTrue(
            resumed.features.available(
                SessionHistory,
            ).page().items.last().info.position > page.items.last().info.position,
        )
        second.close()
    }

    @Test
    fun `restart records an interrupted native turn unknown exactly once`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.transport.generation = { args, line ->
            val id = args.last().substringAfter('=')
            line(initFrame(id))
            line(assistantFrame(id))
            awaitCancellation()
        }
        val first = fixture.runtime()
        val session = first.create(CreateSessionRequest(testTarget))
        val send = async { session.features.available(SendsPrompts).send(prompt()) }
        runCurrent()
        val turn = send.await()
        first.close()
        repeat(2) {
            val runtime = fixture.runtime()
            val restored = runtime.attach(session.ref, ResumeSessionRequest(testTarget))
            assertEquals(
                TurnOutcome.Unknown,
                assertIs<ActiveSessionState.Ready>(restored.state.value).lastTurn?.outcome,
            )
            val record = assertNotNull(fixture.catalog.find(session.ref))
            assertNull(record.activeTurn)
            assertEquals(
                1,
                record.history.events.count { (_, event) -> event is SessionEvent.TurnFinished && event.turn == turn },
            )
            runtime.close()
        }
    }

    @Test
    fun `restart preserves certain non delivery and retries with a fresh native session`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val failure = EngineFailure.Engine(EngineFailureReason.RequirementsNotMet)
        fixture.transport.generation = { _, _ -> throw EngineException(failure) }
        val first = fixture.runtime()
        val session = first.create(CreateSessionRequest(testTarget))
        val send = async {
            assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(prompt()) }
        }
        runCurrent()
        assertEquals(failure, send.await().failure)
        first.close()
        val second = fixture.runtime()
        val restored = second.attach(session.ref, ResumeSessionRequest(testTarget))
        assertEquals(
            TurnOutcome.Failed(failure),
            assertIs<ActiveSessionState.Ready>(restored.state.value).lastTurn?.outcome,
        )
        fixture.transport.generation = { args, line ->
            assertTrue("--session-id=${session.ref.nativeId}" in args)
            line(resultFrame(session.ref.nativeId))
            0
        }
        val retry = async { restored.features.available(SendsPrompts).send(prompt("retry")) }
        runCurrent()
        retry.await()
        second.close()
    }

    @Test
    fun `hosted CLI flags retain empty native tools and combine strict MCP servers`() {
        val directory = Files.createTempDirectory("claude-hosted-test")
        val config = claudeHostedConfig(
            AgentToolBridgeEndpoint("http://127.0.0.1:42", "host-token"),
            SearchBridgeEndpoint("http://127.0.0.1:43", "search-token"),
            directory,
        )
        try {
            val servers = Json.parseToJsonElement(Files.readString(config)) as JsonObject
            assertEquals(setOf("heartbeat_tools", "heartbeat_search"), assertIs<JsonObject>(servers["mcpServers"]).keys)
            val args = claudeHostedArguments(
                claudeArguments(search = true),
                config,
                directory.resolve("prompt.txt"),
                true,
            )
            assertTrue("--tools=" in args)
            assertTrue("--strict-mcp-config" in args)
            assertTrue("--permission-mode=dontAsk" in args)
            assertTrue("--allowedTools=mcp__heartbeat_tools__*,mcp__heartbeat_search__*" in args)
            assertFalse(args.any { "dangerously-skip-permissions" in it || "WebSearch" in it || "host-token" in it })
        } finally {
            Files.deleteIfExists(config)
            Files.deleteIfExists(directory)
        }
    }
}

private fun nativeId(args: List<String>): String =
    args.first { it.startsWith("--session-id=") || it.startsWith("--resume=") }.substringAfter('=')

private class TestAgentTools : ProfileAgentTools {
    override suspend fun specifications(workspace: WorkspaceRef?) = listOf(
        AgentToolSpec("run_command", "Run command", JsonObject(emptyMap())),
    )
    override suspend fun instructions(workspace: WorkspaceRef?) = "Use the hosted tools for coding."
    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject) = AgentToolResult(
        "done",
    )
}

private class TestAgentBridge : AgentToolBridge {
    override val isAvailable = true
    var closed = 0
    val attached = mutableListOf<WorkspaceRef?>()
    private var factory: (suspend () -> AgentToolContext?)? = null
    suspend fun context() = factory?.invoke()
    override suspend fun attach(
        workspace: WorkspaceRef?,
        context: suspend () -> AgentToolContext?,
    ): AgentToolBridgeAttachment {
        attached += workspace
        factory = context
        return object : AgentToolBridgeAttachment {
            override val endpoint = AgentToolBridgeEndpoint("http://127.0.0.1:42", "test-token")
            override fun close() {
                closed++
            }
        }
    }
}
