package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientFactory
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientHandler
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpConnection
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpImplementation
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpInitialization
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionOption
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionOutcome
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionRequest
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPromptResult
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSession
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSessionUpdate
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpTransport
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeAttachment
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeEndpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.AppliesTrustLevels
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnavailableAgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("LargeClass") // Shared runtime fixture exercises the complete session lifecycle.
class KimiRuntimeTest {
    @Test
    fun `hosted admissions are bound to one call and expire with the turn`() = runTest {
        val connection = FakeConnection()
        val bridge = TestBridge()
        val tools = object : ProfileAgentTools by TestAgentTools {
            override suspend fun specifications(workspace: WorkspaceRef?) =
                listOf(AgentToolSpec("tool", "Tool", JsonObject(emptyMap())))
            override suspend fun specifications(scope: AgentToolScope) = specifications(scope.workspace)
        }
        val runtime = runtime(connection, this, tools = tools, bridge = bridge)
        val session = runtime.create(CreateSessionRequest(target, areDetachedToolsEnabled = true))
        assertEquals(1, connection.servers.size)
        assertNull(bridge.context("unknown", "tool"))
        session.sender().send(prompt("first"))
        fun admission(call: String, origin: String = connection.turnToken) = AcpPermissionRequest(
            session.ref.nativeId,
            buildJsonObject {
                put("title", "mcp__heartbeat_tools__tool")
                put(
                    "_meta",
                    buildJsonObject {
                        put("heartbeatCall", call)
                        put("heartbeatTurn", origin)
                    },
                )
            },
            listOf(AcpPermissionOption("allow", "Allow", "allow_once")),
        )
        assertEquals(AcpPermissionOutcome.Selected("allow"), runtime.requestPermission(admission("one")))
        assertNull(bridge.context("forged", "tool"))
        assertEquals(session.ref, bridge.context("one", "tool")?.session)
        assertNull(bridge.context("one", "tool"))
        assertEquals(AcpPermissionOutcome.Cancelled, runtime.requestPermission(admission("one")))
        assertEquals(AcpPermissionOutcome.Selected("allow"), runtime.requestPermission(admission("old")))
        val oldTurn = connection.turnToken
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
        connection.prompt = CompletableDeferred()
        session.sender().send(prompt("second"))
        assertEquals(AcpPermissionOutcome.Cancelled, runtime.requestPermission(admission("late", oldTurn)))
        assertNull(bridge.context("old", "tool"))
        assertEquals(AcpPermissionOutcome.Selected("allow"), runtime.requestPermission(admission("next")))
        assertNull(bridge.context("next", "other"))
        assertNull(bridge.context("next", "tool"))
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
        runtime.close()
        assertTrue(bridge.closed)
    }

    @Test
    fun `question awaits the selected offered answer`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        val turn = session.sender().send(prompt("ask"))
        val request = AcpPermissionRequest(
            session.ref.nativeId,
            buildJsonObject {
                put("title", "AskUserQuestion")
                put(
                    "_meta",
                    buildJsonObject {
                        put("heartbeatTurn", connection.turnToken)
                        put("heartbeatQuestion", true)
                    },
                )
            },
            listOf(
                AcpPermissionOption("a", "A", "allow_once"),
                AcpPermissionOption("b", "B", "allow_once"),
                AcpPermissionOption("skip", "Skip", "reject_once"),
            ),
        )
        val answer = async { runtime.requestPermission(request) }
        testScheduler.runCurrent()
        val pending = assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value).requests.single()
        assertEquals(listOf("A", "B", "Skip"), pending.options.map { it.title })
        session.permissions().respond(PermissionDecision(turn, pending.id, PermissionOptionId("b")))
        assertEquals(AcpPermissionOutcome.Selected("b"), answer.await())
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `late native approval cannot acquire a later turn`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        session.sender().send(prompt("first"))
        val origin = connection.turnToken
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
        connection.prompt = CompletableDeferred()
        session.sender().send(prompt("second"))
        val request = AcpPermissionRequest(
            session.ref.nativeId,
            buildJsonObject {
                put("title", "Bash")
                put("kind", "execute")
                put("_meta", buildJsonObject { put("heartbeatTurn", origin) })
            },
            listOf(AcpPermissionOption("allow", "Allow", "allow_once")),
        )
        assertEquals(AcpPermissionOutcome.Cancelled, runtime.requestPermission(request))
        assertIs<ActiveSessionState.Running>(session.state.value)
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `runtime revokes capability before waiting for transport close`() = runTest {
        val connection = FakeConnection().apply { closeBarrier = CompletableDeferred() }
        val bridge = TestBridge()
        val tools = object : ProfileAgentTools by TestAgentTools {
            override suspend fun specifications(scope: AgentToolScope) =
                listOf(AgentToolSpec("tool", "Tool", JsonObject(emptyMap())))
        }
        val runtime = runtime(connection, this, tools = tools, bridge = bridge)
        runtime.create(CreateSessionRequest(target, areDetachedToolsEnabled = true))
        val closing = async { runtime.close() }
        testScheduler.runCurrent()
        assertTrue(bridge.closed)
        assertFalse(closing.isCompleted)
        connection.closeBarrier?.complete(Unit)
        closing.await()
    }

    @Test
    fun `runtime revokes bridge while transport cancellation is blocked`() = runTest {
        val connection = FakeConnection().apply { cancellationBarrier = CompletableDeferred() }
        val bridge = TestBridge()
        val tools = object : ProfileAgentTools by TestAgentTools {
            override suspend fun specifications(scope: AgentToolScope) =
                listOf(AgentToolSpec("tool", "Tool", JsonObject(emptyMap())))
        }
        val runtime = runtime(connection, this, tools = tools, bridge = bridge)
        val session = runtime.create(CreateSessionRequest(target, areDetachedToolsEnabled = true))
        val turn = session.sender().send(prompt("first"))
        val cancelling = async { session.canceller().cancel(turn) }
        testScheduler.runCurrent()
        assertFalse(cancelling.isCompleted)
        runtime.close()
        assertTrue(bridge.closed)
        assertNull(bridge.context("any", "tool"))
        connection.cancellationBarrier?.complete(Unit)
        cancelling.await()
        connection.prompt.complete(AcpPromptResult("cancelled"))
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `resume supplies scoped bridge and detached opt out supplies none`() = runTest {
        val tools = object : ProfileAgentTools by TestAgentTools {
            override suspend fun specifications(scope: AgentToolScope) =
                listOf(AgentToolSpec("tool", "Tool", JsonObject(emptyMap())))
        }
        val capabilities = buildJsonObject { put("loadSession", true) }
        val connection = FakeConnection()
        val bridge = TestBridge()
        val runtime = runtime(connection, this, capabilities, tools, bridge)
        val session = runtime.resume(ResumeSessionRequest(target, areDetachedToolsEnabled = true), "restored")
        assertEquals("restored", session.ref.nativeId)
        assertEquals(1, connection.servers.size)
        assertEquals(session.ref, bridge.scope?.session)
        runtime.close()
        assertTrue(bridge.closed)

        val noToolsConnection = FakeConnection()
        val noToolsBridge = TestBridge()
        val noToolsRuntime = runtime(noToolsConnection, this, tools = tools, bridge = noToolsBridge)
        noToolsRuntime.create(CreateSessionRequest(target))
        assertTrue(noToolsConnection.servers.isEmpty())
        assertNull(noToolsBridge.scope)
        noToolsRuntime.close()
    }

    @Test
    fun `failed session configuration revokes bridge capability`() = runTest {
        val connection = FakeConnection().apply {
            metadata = Json.parseToJsonElement(
                """{"configOptions":[{"id":"model"}]}""",
            ).jsonObject
            configFailure = IllegalStateException("configuration failed")
        }
        val bridge = TestBridge()
        val tools = object : ProfileAgentTools by TestAgentTools {
            override suspend fun specifications(scope: AgentToolScope) =
                listOf(AgentToolSpec("tool", "Tool", JsonObject(emptyMap())))
        }
        val runtime = runtime(connection, this, tools = tools, bridge = bridge)
        assertFailsWith<EngineException> {
            runtime.create(CreateSessionRequest(target, areDetachedToolsEnabled = true))
        }
        assertTrue(bridge.closed)
        runtime.close()
    }

    @Test
    fun `persistent CLI approval is never selected by Heartbeat`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        session.sender().send(prompt("no-persistent-approval"))
        val result = runtime.requestPermission(
            AcpPermissionRequest(
                session.ref.nativeId,
                buildJsonObject { put("kind", "execute") },
                listOf(AcpPermissionOption("always", "Always allow", "allow_always")),
            ),
        )
        assertEquals(AcpPermissionOutcome.Cancelled, result)
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `context usage arriving after turn completion is retained by the session`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        val usage = (session.features.resolve(SessionContextUsage) as FeatureAccess.Available).feature
        assertNull(usage.state.value)
        session.sender().send(prompt("usage"))
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
        connection.update("""{"sessionUpdate":"usage_update","used":1200,"size":262144}""")
        assertEquals(1200L, usage.state.value?.usedTokens)
        assertEquals(262144L, usage.state.value?.capacityTokens)
    }

    @Test
    fun `prompt merges streamed text and completes turn`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        assertIs<FeatureAccess.Available<AppliesTrustLevels>>(session.features.resolve(AppliesTrustLevels))
        val turn = session.sender().send(PromptRequest(RequestId("request"), listOf(ContentPart.Text("Hello"))))
        connection.update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Hel"}}""")
        connection.update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"lo"}}""")
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()

        assertEquals(TurnOutcome.Completed, session.current().lastTurn?.outcome)
        val history = session.history().page()
        val answer = history.items.filterIsInstance<SessionItem.Message>().last()
        assertEquals("Hello", (answer.parts.single() as ContentPart.Text).text)
        assertEquals(turn, session.current().lastTurn?.id)
    }

    @Test
    fun `permission waits for matching user decision`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        val turn = session.sender().send(PromptRequest(RequestId("request"), listOf(ContentPart.Text("Edit"))))
        val permission = async {
            runtime.requestPermission(
                AcpPermissionRequest(
                    session.ref.nativeId,
                    buildJsonObject {
                        put("toolCallId", "call")
                        put("title", "Edit file")
                        put("_meta", buildJsonObject { put("heartbeatTurn", connection.turnToken) })
                    },
                    listOf(AcpPermissionOption("allow", "Allow", "allow_once")),
                ),
            )
        }
        testScheduler.advanceUntilIdle()
        assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value)
        session.permissions().respond(
            PermissionDecision(
                turn,
                (session.state.value as ActiveSessionState.AwaitingUserAction).requests.single().id,
                PermissionOptionId("allow"),
            ),
        )
        assertEquals(AcpPermissionOutcome.Selected("allow"), permission.await())
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
        assertEquals(TurnOutcome.Completed, session.current().lastTurn?.outcome)
    }

    @Test
    fun `cancel uses native session and keeps terminal outcome`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        val turn = session.sender().send(PromptRequest(RequestId("request"), listOf(ContentPart.Text("Work"))))
        session.canceller().cancel(turn)
        assertEquals(session.ref.nativeId, connection.cancelled)
        connection.prompt.complete(AcpPromptResult("cancelled"))
        testScheduler.advanceUntilIdle()
        assertEquals(TurnOutcome.Cancelled, session.current().lastTurn?.outcome)
    }

    @Test
    fun `separate turns and tools preserve message identities and tool fields`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        val first = session.sender().send(prompt("first"))
        connection.update("""{"sessionUpdate":"agent_message_chunk","content":{"text":"Before"}}""")
        connection.update(
            buildJsonObject {
                put("sessionUpdate", "tool_call")
                put("toolCallId", "call")
                put("title", "Read file")
                put("rawInput", buildJsonObject { put("path", "a") })
                put("status", "in_progress")
            }.toString(),
        )
        connection.update("""{"sessionUpdate":"tool_call_update","toolCallId":"call","status":"completed"}""")
        connection.update("""{"sessionUpdate":"agent_message_chunk","content":{"text":"After"}}""")
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
        connection.prompt = CompletableDeferred()
        val second = session.sender().send(prompt("second"))
        connection.update("""{"sessionUpdate":"agent_message_chunk","content":{"text":"Next"}}""")
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
        val items = session.history().page().items
        val answers = items.filterIsInstance<SessionItem.Message>().filter { it.role == MessageRole.Assistant }
        assertEquals(listOf("Before", "After", "Next"), answers.map { (it.parts.single() as ContentPart.Text).text })
        assertEquals(listOf(first, first, second), answers.map { it.info.turn })
        assertEquals(3, answers.map { it.info.id }.distinct().size)
        val call = items.filterIsInstance<SessionItem.ToolCall>().single()
        assertEquals("Read file", call.name)
        assertEquals("{\"path\":\"a\"}", call.arguments)
        assertEquals(ToolCallStatus.Succeeded, call.status)
        assertTrue(
            answers[0].info.position < call.info.position && call.info.position < answers[1].info.position,
        )
    }

    @Test
    fun `closing one lease permits reattach without stopping the turn`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val first = runtime.create(CreateSessionRequest(target))
        val turn = first.sender().send(prompt("first"))
        first.close()
        assertEquals(ActiveSessionState.Closed, first.state.value)
        val attacher = (runtime.features.resolve(AttachesSessions) as FeatureAccess.Available).feature
        val second = attacher.attach(first.ref, ResumeSessionRequest(target))
        assertEquals(turn, assertIs<ActiveSessionState.Running>(second.state.value).turn.id)
        val reconciler = (second.features.resolve(ReconcilesSession) as FeatureAccess.Available).feature
        reconciler.synchronize()
        assertIs<ActiveSessionState.Running>(second.state.value)
        assertFailsWith<EngineException> {
            first.sender().send(prompt("closed"))
        }
        assertFailsWith<EngineException> {
            second.sender().send(prompt("busy"))
        }
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
        assertEquals(ActiveSessionState.Closed, first.state.value)
        assertEquals(TurnOutcome.Completed, second.current().lastTurn?.outcome)
        connection.prompt = CompletableDeferred()
        second.sender().send(prompt("new"))
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `concurrent submissions accept exactly one turn`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        val accepted = async { session.sender().send(prompt("first")) }
        val rejected = async {
            assertFailsWith<EngineException> { session.sender().send(prompt("second")) }
        }
        assertEquals(accepted.await(), assertIs<ActiveSessionState.Running>(session.state.value).turn.id)
        val failure = assertIs<EngineFailure.Session>(rejected.await().failure)
        assertEquals(SessionFailureReason.Busy, failure.reason)
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `stock CLI without mandatory permission gate is closed before sessions`() = runTest {
        val connection = FakeConnection()
        val transport = object : AcpTransport {
            override suspend fun receive(): String? = null
            override suspend fun send(frame: String) = Unit
            override fun close() = Unit
        }
        val clients = object : AcpClientFactory {
            override fun connect(
                transport: AcpTransport,
                scope: CoroutineScope,
                handler: AcpClientHandler,
            ): AcpConnection = connection
        }
        val failure = assertFailsWith<EngineException> {
            KimiRuntime.open(
                RuntimeIdentity(KimiEngine.Id, KimiEngine.AuthSource, AuthRevision.Known("one")),
                transport,
                clients,
                this@runTest,
                directory = { "/workspace" },
                toggles = TestToggles(),
            )
        }
        assertEquals(EngineFailureReason.RequirementsNotMet, assertIs<EngineFailure.Engine>(failure.failure).reason)
        assertTrue(connection.closed)
    }

    @Test
    fun `cancelled initialization closes the connection`() = runTest {
        val connection = FakeConnection().apply { initialization = CompletableDeferred() }
        val transport = object : AcpTransport {
            override suspend fun receive(): String? = null
            override suspend fun send(frame: String) = Unit
            override fun close() = Unit
        }
        val clients = object : AcpClientFactory {
            override fun connect(
                transport: AcpTransport,
                scope: CoroutineScope,
                handler: AcpClientHandler,
            ): AcpConnection = connection
        }
        val opening = async {
            KimiRuntime.open(
                RuntimeIdentity(KimiEngine.Id, KimiEngine.AuthSource, AuthRevision.Known("one")),
                transport,
                clients,
                this@runTest,
                directory = { "/workspace" },
                toggles = TestToggles(),
            )
        }
        testScheduler.runCurrent()
        opening.cancel()
        opening.join()
        assertTrue(connection.closed)
    }

    @Test
    fun `multiple permissions retain pending requests and ignore stale decisions`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        val turn = session.sender().send(prompt("request"))
        fun permission(title: String) = AcpPermissionRequest(
            session.ref.nativeId,
            buildJsonObject {
                put("toolCallId", "shared-call")
                put("title", title)
                put("_meta", buildJsonObject { put("heartbeatTurn", connection.turnToken) })
            },
            listOf(AcpPermissionOption("allow", "Allow", "allow_once")),
        )
        val first = async { runtime.requestPermission(permission("First permission")) }
        val second = async { runtime.requestPermission(permission("Second permission")) }
        testScheduler.runCurrent()
        val requests = assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value).requests
        assertEquals(2, requests.size)
        assertEquals(2, requests.map { it.id }.distinct().size)
        val (firstRequest, secondRequest) = requests
        session.permissions().respond(
            PermissionDecision(TurnId("stale-turn"), firstRequest.id, PermissionOptionId("allow")),
        )
        session.permissions().respond(
            PermissionDecision(turn, PermissionRequestId("missing"), PermissionOptionId("allow")),
        )
        session.permissions().respond(
            PermissionDecision(turn, firstRequest.id, PermissionOptionId("missing")),
        )
        assertEquals(requests, assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value).requests)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        val firstDecision = PermissionDecision(turn, firstRequest.id, PermissionOptionId("allow"))
        session.permissions().respond(firstDecision)
        assertEquals(AcpPermissionOutcome.Selected("allow"), first.await())
        assertEquals(
            listOf(secondRequest),
            assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value).requests,
        )
        session.permissions().respond(firstDecision)
        assertFalse(second.isCompleted)
        session.permissions().respond(PermissionDecision(turn, secondRequest.id, PermissionOptionId("allow")))
        assertEquals(AcpPermissionOutcome.Selected("allow"), second.await())
        val running = assertIs<ActiveSessionState.Running>(session.state.value)
        assertEquals(setOf(firstRequest.id, secondRequest.id), running.turn.resolvedPermissions)
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `runtime shutdown closes all leases and rejects further sends`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val first = runtime.create(CreateSessionRequest(target))
        first.sender().send(prompt("request"))
        val attacher = (runtime.features.resolve(AttachesSessions) as FeatureAccess.Available).feature
        val second = attacher.attach(first.ref, ResumeSessionRequest(target))
        runtime.close()
        assertTrue(connection.closed)
        assertEquals(ActiveSessionState.Closed, first.state.value)
        assertEquals(ActiveSessionState.Closed, second.state.value)
        val rejection = assertFailsWith<EngineException> { second.sender().send(prompt("after-close")) }
        assertEquals(LifecycleFailureReason.ProfileClosed, assertIs<EngineFailure.Lifecycle>(rejection.failure).reason)
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
        assertEquals(ActiveSessionState.Closed, first.state.value)
        assertEquals(ActiveSessionState.Closed, second.state.value)
    }

    @Test
    fun `replayed user messages retain plain text content`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        connection.update("""{"sessionUpdate":"user_message_chunk","content":{"type":"text","text":"User input"}}""")
        val message = assertIs<SessionItem.Message>(session.history().page().items.single())
        assertEquals(MessageRole.User, message.role)
        assertEquals(ContentPart.Text("User input"), message.parts.single())
    }

    @Test
    fun `cancellation waits until the prompt reaches the native connection`() = runTest {
        val connection = FakeConnection().apply { submissionBarrier = CompletableDeferred() }
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        val sending = async { session.sender().send(prompt("request")) }
        testScheduler.runCurrent()
        val turn = assertIs<ActiveSessionState.Running>(session.state.value).turn.id
        val cancelling = async { session.canceller().cancel(turn) }
        testScheduler.runCurrent()
        assertFalse(sending.isCompleted)
        assertFalse(cancelling.isCompleted)
        assertNull(connection.cancelled)
        assertIs<ActiveSessionState.Interrupting>(session.state.value)
        requireNotNull(connection.submissionBarrier).complete(Unit)
        assertEquals(turn, sending.await())
        cancelling.await()
        assertEquals(session.ref.nativeId, connection.cancelled)
        connection.prompt.complete(AcpPromptResult("cancelled"))
        testScheduler.advanceUntilIdle()
        assertEquals(TurnOutcome.Cancelled, session.current().lastTurn?.outcome)
    }

    @Test
    fun `cancel race does not interrupt the following turn`() = runTest {
        val connection = FakeConnection().apply { cancellationBarrier = CompletableDeferred() }
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        val first = session.sender().send(prompt("first"))
        val cancelling = async { session.canceller().cancel(first) }
        testScheduler.runCurrent()
        assertFalse(cancelling.isCompleted)
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.runCurrent()
        requireNotNull(connection.cancellationBarrier).complete(Unit)
        cancelling.await()
        testScheduler.advanceUntilIdle()
        assertEquals(TurnOutcome.Completed, session.current().lastTurn?.outcome)
        connection.prompt = CompletableDeferred()
        connection.cancelled = null
        val second = session.sender().send(prompt("second"))
        session.canceller().cancel(first)
        assertNull(connection.cancelled)
        assertEquals(second, assertIs<ActiveSessionState.Running>(session.state.value).turn.id)
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
        assertEquals(TurnOutcome.Completed, session.current().lastTurn?.outcome)
    }

    @Test
    fun `native disconnect retires runtime and closes active leases`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        session.sender().send(prompt("request"))
        connection.prompt.completeExceptionally(AcpException.Disconnected())
        testScheduler.advanceUntilIdle()
        assertTrue(runtime.isClosed)
        assertTrue(connection.closed)
        assertEquals(ActiveSessionState.Closed, session.state.value)
        val rejected = assertFailsWith<EngineException> { session.sender().send(prompt("after-disconnect")) }
        assertEquals(LifecycleFailureReason.ProfileClosed, assertIs<EngineFailure.Lifecycle>(rejected.failure).reason)
    }

    @Test
    fun `null tool input update preserves previously reported arguments`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        session.sender().send(prompt("tool"))
        connection.update(
            """{"sessionUpdate":"tool_call","toolCallId":"call","title":"Read","rawInput":{"path":"a"}}""",
        )
        connection.update(
            """{"sessionUpdate":"tool_call_update","toolCallId":"call","rawInput":null,"status":"completed"}""",
        )
        val tool = session.history().page().items.filterIsInstance<SessionItem.ToolCall>().single()
        assertEquals("{\"path\":\"a\"}", tool.arguments)
        assertEquals(ToolCallStatus.Succeeded, tool.status)
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `thought chunks stay reasoning parts of assistant message`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        session.sender().send(prompt("reasoning"))
        connection.update("""{"sessionUpdate":"agent_thought_chunk","content":{"text":"Think"}}""")
        connection.update("""{"sessionUpdate":"agent_message_chunk","content":{"text":"Answer"}}""")
        val message = session.history().page().items.filterIsInstance<SessionItem.Message>().last()
        assertEquals(MessageRole.Assistant, message.role)
        assertEquals(ContentPart.Reasoning("Think"), message.parts[0])
        assertEquals(ContentPart.Text("Answer"), message.parts[1])
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `selected model effort and safe native mode are applied through ACP`() = runTest {
        val connection = FakeConnection().apply {
            metadata = Json.parseToJsonElement(
                """{"configOptions":[
                    {"id":"model","options":[]},
                    {"id":"thinking","category":"thought_level","options":[{"value":"low"},{"value":"max"}]},
                    {"id":"mode","options":[]}
                ]}""",
            ).jsonObject
        }
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        connection.beforeConfigResponse = {
            connection.update("""{"sessionUpdate":"config_option_update","configOptions":[]}""")
            connection.update("""{"sessionUpdate":"usage_update","used":1200,"size":262144}""")
        }
        withTimeout(1000) { session.sender().send(prompt("effort").copy(reasoningEffort = "max")) }
        assertEquals("kimi-code/kimi-for-coding", connection.configuration["model"])
        assertEquals("default", connection.configuration["mode"])
        assertEquals("max", connection.configuration["thinking"])
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `session configuration failure maps to an engine failure`() = runTest {
        val connection = FakeConnection().apply {
            metadata = Json.parseToJsonElement("""{"configOptions":[{"id":"model","options":[]}]}""").jsonObject
            configFailure = AcpException.Remote(-32602, "Invalid params")
        }
        val runtime = runtime(connection, this)
        val failure = assertFailsWith<EngineException> { runtime.create(CreateSessionRequest(target)) }
        assertIs<EngineFailure.Transport>(failure.failure)
        connection.configFailure = null
        val session = runtime.create(CreateSessionRequest(target))
        connection.prompt.complete(AcpPromptResult("end_turn"))
        session.sender().send(prompt("next"))
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
    }

    private fun prompt(id: String) = PromptRequest(RequestId(id), listOf(ContentPart.Text("Hello")))

    private fun runtime(
        connection: FakeConnection,
        scope: CoroutineScope,
        capabilities: JsonObject = JsonObject(emptyMap()),
        tools: ProfileAgentTools = TestAgentTools,
        bridge: AgentToolBridge = UnavailableAgentToolBridge,
    ): KimiRuntime {
        val runtime = KimiRuntime(
            RuntimeIdentity(KimiEngine.Id, KimiEngine.AuthSource, AuthRevision.Known("one")),
            connection,
            CoroutineScope(scope.coroutineContext + Job((scope as TestScope).backgroundScope.coroutineContext[Job])),
            AcpInitialization(1, capabilities),
            directory = { "/workspace" },
            toggles = TestToggles(),
            tools = tools,
            bridge = bridge,
        )
        connection.runtime = runtime
        return runtime
    }

    private val target = EngineTarget(KimiEngine.Id, EngineBindingId("binding"), ModelId("kimi-for-coding"))
}

private class TestBridge : AgentToolBridge {
    override val isAvailable = true
    var closed = false
    var scope: AgentToolScope? = null
    lateinit var context: suspend (String, String) -> AgentToolContext?
    override suspend fun attach(
        workspace: WorkspaceRef?,
        context: suspend () -> AgentToolContext?,
    ): AgentToolBridgeAttachment = error("Expected correlated attachment")
    override suspend fun attachCorrelated(
        scope: AgentToolScope,
        context: suspend (String, String) -> AgentToolContext?,
    ): AgentToolBridgeAttachment {
        this.context = context
        this.scope = scope
        return object : AgentToolBridgeAttachment {
            override val endpoint = AgentToolBridgeEndpoint("http://127.0.0.1:1", "secret")
            override fun close() {
                closed = true
            }
        }
    }
}

private class FakeConnection : AcpConnection {
    var prompt = CompletableDeferred<AcpPromptResult>()
    var servers: List<JsonObject> = emptyList()
    var turnToken = ""
    var cancelled: String? = null
    var submissionBarrier: CompletableDeferred<Unit>? = null
    var cancellationBarrier: CompletableDeferred<Unit>? = null
    var metadata = JsonObject(emptyMap())
    var content = listOf<JsonObject>()
    var configFailure: Exception? = null
    var beforeConfigResponse: (suspend () -> Unit)? = null
    val configuration = mutableMapOf<String, String>()
    lateinit var runtime: KimiRuntime

    override suspend fun initialize(client: AcpImplementation) = initialization?.await() ?: AcpInitialization(1)
    override suspend fun authenticate(methodId: String) = Unit
    override suspend fun newSession(cwd: String) = AcpSession("native", metadata)
    override suspend fun newSession(cwd: String, servers: List<JsonObject>): AcpSession {
        this.servers = servers
        return newSession(cwd)
    }
    override suspend fun loadSession(sessionId: String, cwd: String, servers: List<JsonObject>): AcpSession {
        this.servers = servers
        return loadSession(sessionId, cwd)
    }
    override suspend fun loadSession(sessionId: String, cwd: String) = AcpSession(sessionId, metadata)
    override suspend fun prompt(sessionId: String, content: List<JsonObject>, metadata: JsonObject): AcpPromptResult {
        turnToken = metadata["heartbeatTurn"]?.toString()?.trim('"').orEmpty()
        return prompt(sessionId, content)
    }
    override suspend fun prompt(sessionId: String, content: List<JsonObject>): AcpPromptResult {
        this.content = content
        submissionBarrier?.await()
        runtime.onPromptSubmitted(sessionId)
        return prompt.await()
    }
    override suspend fun setConfigOption(sessionId: String, optionId: String, value: String) {
        configFailure?.let { throw it }
        configuration[optionId] = value
        beforeConfigResponse?.invoke()
    }
    override suspend fun cancel(sessionId: String) {
        cancellationBarrier?.await()
        cancelled = sessionId
    }
    var closed = false
    var closeBarrier: CompletableDeferred<Unit>? = null
    var initialization: CompletableDeferred<AcpInitialization>? = null
    override suspend fun close() {
        closeBarrier?.await()
        closed = true
    }

    suspend fun update(update: String) {
        val json = Json.parseToJsonElement(update).jsonObject
        runtime.onSessionUpdate(AcpSessionUpdate("native", json))
    }
}

private fun ActiveSession.sender() = (features.resolve(SendsPrompts) as FeatureAccess.Available).feature

private fun ActiveSession.canceller() = (features.resolve(CancelsTurns) as FeatureAccess.Available).feature

private fun ActiveSession.permissions() = (features.resolve(RequestsPermissions) as FeatureAccess.Available).feature

private fun ActiveSession.history() = (features.resolve(SessionHistory) as FeatureAccess.Available).feature

private fun ActiveSession.current() = state.value as ActiveSessionState.Ready

private class TestToggles : FeatureToggles {
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(value(toggle))

    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = value(toggle)

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> value(toggle: FeatureToggle<T>): T = when (toggle) {
        is FeatureToggle.Flag -> true as T
        is FeatureToggle.Choice -> toggle.default
    }
}

private object TestAgentTools : ProfileAgentTools {
    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> = emptyList()
    override suspend fun instructions(workspace: WorkspaceRef?): String = ""
    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult =
        AgentToolResult("Unsupported", isError = true)

    override suspend fun authorizeNative(context: AgentToolContext, call: NativeToolCall): NativeVerdict =
        if (context.permissions.request(AgentToolApproval(call.name, "Kimi CLI requests permission"))) {
            NativeVerdict.Allow
        } else {
            NativeVerdict.Deny("Denied")
        }
}
