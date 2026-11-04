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
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
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
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelled resume closes late loaded session without deleting history`() = runTest {
        val response = CompletableDeferred<Unit>()
        val connection = FakeConnection().apply { loadSessionBarrier = response }
        val capabilities = buildJsonObject {
            put("loadSession", true)
            put("sessionCapabilities", JsonObject(mapOf("close" to JsonObject(emptyMap()))))
        }
        val runtime = runtime(connection, this, capabilities)
        val ref = SessionRef(KimiEngine.Id, KimiEngine.SessionSource, "native")

        val task = async { runtime.attach(ref, ResumeSessionRequest(target)) }
        runCurrent()
        assertEquals(1, connection.loadedSessions)
        task.cancel()
        runCurrent()
        assertFalse(task.isCompleted)
        response.complete(Unit)
        task.join()

        assertTrue(task.isCancelled)
        assertEquals("native", connection.closedSession)
        assertNull(connection.deletedSession)
        assertFalse(runtime.isClosed)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `unknown resumed session outcome retires runtime after bounded wait`() = runTest {
        val connection = FakeConnection().apply { loadSessionBarrier = CompletableDeferred() }
        val runtime = runtime(connection, this, buildJsonObject { put("loadSession", true) })
        val ref = SessionRef(KimiEngine.Id, KimiEngine.SessionSource, "native")

        val task = async { runtime.attach(ref, ResumeSessionRequest(target)) }
        runCurrent()
        task.cancel()
        testScheduler.advanceTimeBy(60_001)
        runCurrent()
        task.join()

        assertTrue(task.isCancelled)
        assertTrue(runtime.isClosed)
        assertTrue(connection.closed)
        assertNull(connection.deletedSession)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `unknown new session outcome retires runtime after bounded wait`() = runTest {
        val connection = FakeConnection().apply { newSessionBarrier = CompletableDeferred() }
        val runtime = runtime(connection, this)

        val task = async { runtime.create(CreateSessionRequest(target)) }
        runCurrent()
        task.cancel()
        testScheduler.advanceTimeBy(60_001)
        runCurrent()
        task.join()

        assertTrue(task.isCancelled)
        assertTrue(runtime.isClosed)
        assertTrue(connection.closed)
        assertNull(connection.deletedSession)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelled creation waits for late native identity and deletes that session`() = runTest {
        val response = CompletableDeferred<Unit>()
        val connection = FakeConnection().apply { newSessionBarrier = response }
        val capability = JsonObject(mapOf("delete" to JsonObject(emptyMap())))
        val runtime = runtime(connection, this, JsonObject(mapOf("sessionCapabilities" to capability)))

        val task = async { runtime.create(CreateSessionRequest(target)) }
        runCurrent()
        assertEquals(1, connection.newSessions)
        task.cancel()
        runCurrent()
        assertFalse(task.isCompleted)
        response.complete(Unit)
        task.join()

        assertTrue(task.isCancelled)
        assertEquals("native", connection.deletedSession)
        assertFalse(runtime.isClosed)
    }

    @Test
    fun `cancellation before new session handoff deletes only the new session`() = runTest {
        val connection = FakeConnection().apply {
            beforeConfigResponse = { kotlinx.coroutines.currentCoroutineContext().cancel() }
        }
        val capability = JsonObject(mapOf("delete" to JsonObject(emptyMap())))
        val runtime = runtime(connection, this, JsonObject(mapOf("sessionCapabilities" to capability)))

        val task = async { runtime.create(CreateSessionRequest(target)) }
        task.join()

        assertTrue(task.isCancelled)
        assertEquals("native", connection.deletedSession)
        assertFalse(runtime.isClosed)
    }

    @Test
    fun `cancellation before resumed session handoff preserves user history`() = runTest {
        val connection = FakeConnection().apply {
            beforeConfigResponse = { kotlinx.coroutines.currentCoroutineContext().cancel() }
        }
        val capabilities = buildJsonObject {
            put("loadSession", true)
            put("sessionCapabilities", JsonObject(mapOf("close" to JsonObject(emptyMap()))))
        }
        val runtime = runtime(connection, this, capabilities)
        val ref = SessionRef(KimiEngine.Id, KimiEngine.SessionSource, "native")

        val task = async { runtime.attach(ref, ResumeSessionRequest(target)) }
        task.join()

        assertTrue(task.isCancelled)
        assertEquals("native", connection.closedSession)
        assertNull(connection.deletedSession)
        assertFalse(runtime.isClosed)
    }

    @Test
    fun `model update during configuration prevents session handoff`() = runTest {
        val connection = FakeConnection().apply {
            beforeConfigResponse = {
                update(
                    """{"sessionUpdate":"config_option_update","configOptions":[
                        {"id":"model","currentValue":"other"}]}""",
                )
            }
        }
        val deleteCapability = JsonObject(mapOf("delete" to JsonObject(emptyMap())))
        val runtime = runtime(connection, this, JsonObject(mapOf("sessionCapabilities" to deleteCapability)))

        val error = assertFailsWith<EngineException> { runtime.create(CreateSessionRequest(target)) }

        assertEquals(EngineFailure.Session(SessionFailureReason.Changed), error.failure)
        assertEquals("native", connection.deletedSession)
        assertFalse(runtime.isClosed)
    }

    @Test
    fun `matching model update during configuration permits session handoff`() = runTest {
        val connection = FakeConnection().apply {
            beforeConfigResponse = {
                update(
                    """{"sessionUpdate":"config_option_update","configOptions":[
                        {"id":"model","currentValue":"kimi-code/kimi-for-coding"}]}""",
                )
            }
        }
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        assertIs<ActiveSessionState.Ready>(session.state.value)
    }

    @Test
    fun `failed resumed session configuration closes but never deletes user history`() = runTest {
        val connection = FakeConnection().apply {
            beforeConfigResponse = {
                update(
                    """{"sessionUpdate":"config_option_update","configOptions":[
                        {"id":"model","currentValue":"other"}]}""",
                )
            }
        }
        val sessionCapabilities = JsonObject(mapOf("close" to JsonObject(emptyMap())))
        val capabilities = buildJsonObject {
            put("loadSession", true)
            put("sessionCapabilities", sessionCapabilities)
        }
        val runtime = runtime(connection, this, capabilities)
        val ref = SessionRef(KimiEngine.Id, KimiEngine.SessionSource, "native")

        val error = assertFailsWith<EngineException> { runtime.attach(ref, ResumeSessionRequest(target)) }

        assertEquals(EngineFailure.Session(SessionFailureReason.Changed), error.failure)
        assertEquals("native", connection.closedSession)
        assertNull(connection.deletedSession)
        assertFalse(runtime.isClosed)
    }

    @Test
    fun `duplicate native identity never deletes an existing session`() = runTest {
        val connection = FakeConnection()
        val deleteCapability = JsonObject(mapOf("delete" to JsonObject(emptyMap())))
        val runtime = runtime(connection, this, JsonObject(mapOf("sessionCapabilities" to deleteCapability)))
        runtime.create(CreateSessionRequest(target))

        assertFailsWith<IllegalStateException> { runtime.create(CreateSessionRequest(target)) }

        assertNull(connection.deletedSession)
        assertTrue(runtime.isClosed)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `external model change prevents a new turn and retires runtime`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        connection.update(
            """{"sessionUpdate":"config_option_update","configOptions":[
                {"id":"model","currentValue":"other"}]}""",
        )

        assertFailsWith<EngineException> { session.sender().send(prompt("wrong-model")) }
        assertTrue(connection.content.isEmpty())
        testScheduler.runCurrent()
        assertTrue(runtime.isClosed)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `missing model in config update retires an active runtime`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        session.sender().send(prompt("active"))
        connection.update("""{"sessionUpdate":"config_option_update","configOptions":[]}""")
        testScheduler.runCurrent()

        assertTrue(runtime.isClosed)
        assertEquals(ActiveSessionState.Closed, session.state.value)
    }

    @Test
    fun `thinking change that substitutes model is rejected before prompt`() = runTest {
        val connection = FakeConnection().apply {
            metadata = Json.parseToJsonElement(
                """{"configOptions":[{"id":"model","category":"model",
                    "options":[{"value":"kimi-code/kimi-for-coding"}]},
                    {"id":"thinking","category":"thought_level","options":[{"value":"high"}]}]}""",
            ).jsonObject
        }
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        connection.reportedModelAfterThinking = "other"

        val error = assertFailsWith<EngineException> {
            session.sender().send(prompt("selected-model").copy(reasoningEffort = "high"))
        }

        assertEquals(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet), error.failure)
        assertTrue(connection.content.isEmpty())
        assertTrue(runtime.isClosed)
    }

    @Test
    fun `thinking change with a different effective effort is rejected before prompt`() = runTest {
        val connection = FakeConnection().apply {
            metadata = Json.parseToJsonElement(
                """{"configOptions":[{"id":"model","category":"model",
                    "options":[{"value":"kimi-code/kimi-for-coding"}]},
                    {"id":"thinking","category":"thought_level","options":[{"value":"high"}]}]}""",
            ).jsonObject
        }
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        connection.reportedThinking = "low"

        val error = assertFailsWith<EngineException> {
            session.sender().send(prompt("selected-effort").copy(reasoningEffort = "high"))
        }

        assertIs<EngineFailure.Request>(error.failure)
        assertTrue(connection.content.isEmpty())
        assertTrue(runtime.isClosed)
    }

    @Test
    fun `cancelling an in-flight thinking change retires the uncertain runtime`() = runTest {
        val connection = FakeConnection().apply {
            metadata = Json.parseToJsonElement(
                """{"configOptions":[{"id":"model","category":"model",
                    "options":[{"value":"kimi-code/kimi-for-coding"}]},
                    {"id":"thinking","category":"thought_level","options":[{"value":"high"}]}]}""",
            ).jsonObject
        }
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        val entered = CompletableDeferred<Unit>()
        connection.beforeConfigResponse = {
            entered.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        val sending = launch { session.sender().send(prompt("cancel-effort").copy(reasoningEffort = "high")) }
        entered.await()
        sending.cancelAndJoin()

        assertTrue(connection.content.isEmpty())
        assertTrue(runtime.isClosed)
    }

    @Test
    fun `new session rejects a model substituted by the runtime`() = runTest {
        val connection = FakeConnection().apply { reportedModel = "other" }
        val deleteCapability = JsonObject(mapOf("delete" to JsonObject(emptyMap())))
        val runtime = runtime(connection, this, JsonObject(mapOf("sessionCapabilities" to deleteCapability)))
        val failure = assertFailsWith<EngineException> { runtime.create(CreateSessionRequest(target)) }
        assertEquals(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet), failure.failure)
        assertEquals("native", connection.deletedSession)
    }

    @Test
    fun `new session rejects a model changed while setting the mode`() = runTest {
        val connection = FakeConnection().apply {
            reportedModelAfterMode = "other"
            metadata = Json.parseToJsonElement(
                """{"configOptions":[{"id":"model","category":"model",
                    "options":[{"value":"kimi-code/kimi-for-coding"}]},{"id":"mode"}]}""",
            ).jsonObject
        }
        val deleteCapability = JsonObject(mapOf("delete" to JsonObject(emptyMap())))
        val runtime = runtime(connection, this, JsonObject(mapOf("sessionCapabilities" to deleteCapability)))
        val failure = assertFailsWith<EngineException> { runtime.create(CreateSessionRequest(target)) }
        assertEquals(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet), failure.failure)
        assertEquals("native", connection.deletedSession)
    }

    @Test
    fun `new session refuses an unadvertised model and deletes the created session`() = runTest {
        val inputs = listOf(
            """{}""",
            """{"configOptions":[{"id":"model","category":"model","options":[{"value":"other"}]}]}""",
        )
        for (input in inputs) {
            val connection = FakeConnection().apply { metadata = Json.parseToJsonElement(input).jsonObject }
            val deleteCapability = JsonObject(mapOf("delete" to JsonObject(emptyMap())))
            val runtime = runtime(connection, this, JsonObject(mapOf("sessionCapabilities" to deleteCapability)))
            val failure = assertFailsWith<EngineException> { runtime.create(CreateSessionRequest(target)) }
            assertEquals(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet), failure.failure)
            assertEquals("native", connection.deletedSession)
            assertFalse(runtime.isClosed)
            assertTrue(connection.configuration.isEmpty())
        }
    }

    @Test
    fun `failed cleanup of a rejected new session retires its runtime`() = runTest {
        val connection = FakeConnection().apply {
            metadata = JsonObject(emptyMap())
            deleteSessionFailure = IllegalStateException("delete failed")
        }
        val deleteCapability = JsonObject(mapOf("delete" to JsonObject(emptyMap())))
        val runtime = runtime(connection, this, JsonObject(mapOf("sessionCapabilities" to deleteCapability)))
        val failure = assertFailsWith<EngineException> { runtime.create(CreateSessionRequest(target)) }
        assertEquals(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet), failure.failure)
        assertTrue(runtime.isClosed)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `concurrent catalog requests share a session without sharing caller cancellation`() = runTest {
        val barrier = CompletableDeferred<Unit>()
        val connection = FakeConnection().apply { newSessionBarrier = barrier }
        connection.metadata = Json.parseToJsonElement(
            """{"configOptions":[{"category":"model","options":[{"value":"model"}]}]}""",
        ).jsonObject
        val deleteCapability = JsonObject(mapOf("delete" to JsonObject(emptyMap())))
        val runtime = runtime(connection, this, JsonObject(mapOf("sessionCapabilities" to deleteCapability)))
        val first = async { runtime.discover(EngineBindingId("binding")) }
        val second = async { runtime.discover(EngineBindingId("binding")) }
        runCurrent()
        assertEquals(1, connection.newSessions)
        first.cancelAndJoin()
        barrier.complete(Unit)
        assertTrue(second.await().isNotEmpty())
        assertEquals("native", connection.deletedSession)
        assertFalse(runtime.isClosed)
        runtime.discover(EngineBindingId("binding"))
        assertEquals(2, connection.newSessions)
    }

    @Test
    fun `model discovery returns the native catalog for the requested binding and deletes its session`() = runTest {
        val connection = FakeConnection()
        connection.metadata = Json.parseToJsonElement(
            """{"configOptions":[
                {"id":"model","category":"model","options":[{"value":"kimi-code/k3","name":"K3"}]}
            ]}""",
        ).jsonObject
        val deleteCapability = JsonObject(mapOf("delete" to JsonObject(emptyMap())))
        val capabilities = JsonObject(mapOf("sessionCapabilities" to deleteCapability))
        val runtime = runtime(connection, this, capabilities)
        val binding = EngineBindingId("other-binding")

        val models = runtime.discover(binding)

        assertEquals(listOf("kimi-code/k3"), models.map { it.target.model.value })
        assertTrue(models.all { it.target.binding == binding && it.target.engine == KimiEngine.Id })
        assertEquals("native", connection.deletedSession)
    }

    @Test
    fun `model discovery does not invent a model when the native catalog is empty`() = runTest {
        val connection = FakeConnection()
        connection.metadata = JsonObject(emptyMap())
        val deleteCapability = JsonObject(mapOf("delete" to JsonObject(emptyMap())))
        val runtime = runtime(connection, this, JsonObject(mapOf("sessionCapabilities" to deleteCapability)))

        assertTrue(runtime.discover(EngineBindingId("binding")).isEmpty())
        assertEquals("native", connection.deletedSession)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `model discovery deletes its temporary session after caller cancellation`() = runTest {
        val connection = FakeConnection()
        val barrier = CompletableDeferred<Unit>()
        connection.newSessionBarrier = barrier
        val deleteCapability = JsonObject(mapOf("delete" to JsonObject(emptyMap())))
        val capabilities = JsonObject(mapOf("sessionCapabilities" to deleteCapability))
        val runtime = runtime(connection, this, capabilities)
        val discovery = async { runtime.discover(EngineBindingId("binding")) }
        runCurrent()
        discovery.cancel()
        barrier.complete(Unit)
        discovery.cancelAndJoin()
        runCurrent()
        assertEquals("native", connection.deletedSession)
        assertFalse(runtime.isClosed)
    }

    @Test
    fun `model discovery refuses to create a session when deletion is unsupported`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        assertFailsWith<EngineException> { runtime.discover(EngineBindingId("binding")) }
        assertEquals(0, connection.newSessions)
    }

    @Test
    fun `model discovery retires runtime when session deletion fails`() = runTest {
        val connection = FakeConnection().apply { deleteSessionFailure = IllegalStateException("delete failed") }
        val deleteCapability = JsonObject(mapOf("delete" to JsonObject(emptyMap())))
        val capabilities = JsonObject(mapOf("sessionCapabilities" to deleteCapability))
        val runtime = runtime(connection, this, capabilities)
        assertFailsWith<IllegalStateException> { runtime.discover(EngineBindingId("binding")) }
        assertTrue(runtime.isClosed)
        assertTrue(connection.closed)
    }

    @Test
    fun `model discovery deletes its session when catalog metadata is malformed`() = runTest {
        val connection = FakeConnection()
        connection.metadata = Json.parseToJsonElement("""{"configOptions":"invalid"}""").jsonObject
        val deleteCapability = JsonObject(mapOf("delete" to JsonObject(emptyMap())))
        val capabilities = JsonObject(mapOf("sessionCapabilities" to deleteCapability))
        val runtime = runtime(connection, this, capabilities)

        assertFailsWith<IllegalArgumentException> { runtime.discover(EngineBindingId("binding")) }

        assertEquals("native", connection.deletedSession)
        assertFalse(runtime.isClosed)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `model discovery owner waits after caller timeout then retires a stuck runtime`() = runTest {
        val connection = FakeConnection().apply { newSessionBarrier = CompletableDeferred() }
        val deleteCapability = JsonObject(mapOf("delete" to JsonObject(emptyMap())))
        val capabilities = JsonObject(mapOf("sessionCapabilities" to deleteCapability))
        val runtime = runtime(connection, this, capabilities)
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            runtime.discover(EngineBindingId("binding"))
        }
        assertFalse(runtime.isClosed)
        testScheduler.advanceTimeBy(50_001L)
        runCurrent()
        assertTrue(runtime.isClosed)
        assertTrue(connection.closed)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `model discovery owner deletes a session returned after caller timeout`() = runTest {
        val barrier = CompletableDeferred<Unit>()
        val connection = FakeConnection().apply { newSessionBarrier = barrier }
        val deleteCapability = JsonObject(mapOf("delete" to JsonObject(emptyMap())))
        val capabilities = JsonObject(mapOf("sessionCapabilities" to deleteCapability))
        val runtime = runtime(connection, this, capabilities)
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            runtime.discover(EngineBindingId("binding"))
        }
        assertNull(connection.deletedSession)
        barrier.complete(Unit)
        runCurrent()
        assertEquals("native", connection.deletedSession)
        assertFalse(runtime.isClosed)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `model discovery owner waits for deletion after caller timeout`() = runTest {
        val barrier = CompletableDeferred<Unit>()
        val connection = FakeConnection().apply { deleteSessionBarrier = barrier }
        val deleteCapability = JsonObject(mapOf("delete" to JsonObject(emptyMap())))
        val capabilities = JsonObject(mapOf("sessionCapabilities" to deleteCapability))
        val runtime = runtime(connection, this, capabilities)
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            runtime.discover(EngineBindingId("binding"))
        }
        assertNull(connection.deletedSession)
        barrier.complete(Unit)
        runCurrent()
        assertEquals("native", connection.deletedSession)
        assertFalse(runtime.isClosed)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `model discovery owner retires runtime when deletion never completes`() = runTest {
        val connection = FakeConnection().apply { deleteSessionBarrier = CompletableDeferred() }
        val deleteCapability = JsonObject(mapOf("delete" to JsonObject(emptyMap())))
        val capabilities = JsonObject(mapOf("sessionCapabilities" to deleteCapability))
        val runtime = runtime(connection, this, capabilities)
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            runtime.discover(EngineBindingId("binding"))
        }
        assertFalse(runtime.isClosed)
        testScheduler.advanceTimeBy(50_001L)
        runCurrent()
        assertTrue(runtime.isClosed)
        assertNull(connection.deletedSession)
    }

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
    fun `agent revocation blocks pending admissions and cancels running calls`() = runTest {
        val connection = FakeConnection()
        val bridge = TestBridge()
        val tools = object : ProfileAgentTools by TestAgentTools {
            override suspend fun specifications(scope: AgentToolScope) =
                listOf(AgentToolSpec("tool", "Tool", JsonObject(emptyMap())))
        }
        val runtime = runtime(connection, this, tools = tools, bridge = bridge)
        val session = runtime.create(CreateSessionRequest(target, areDetachedToolsEnabled = true))
        session.sender().send(prompt("first"))
        fun approval(call: String) = AcpPermissionRequest(
            session.ref.nativeId,
            buildJsonObject {
                put("title", "mcp__heartbeat_tools__tool")
                put(
                    "_meta",
                    buildJsonObject {
                        put("heartbeatTurn", connection.turnToken)
                        put("heartbeatCall", call)
                    },
                )
            },
            listOf(AcpPermissionOption("allow", "Allow", "allow_once")),
        )
        suspend fun revoke(call: String, origin: String = connection.turnToken) {
            connection.update(
                """{"sessionUpdate":"heartbeat_hosted_revoked","heartbeatTurn":"$origin","heartbeatCall":"$call"}""",
            )
        }
        revoke("before")
        assertEquals(AcpPermissionOutcome.Cancelled, runtime.requestPermission(approval("before")))
        assertEquals(AcpPermissionOutcome.Selected("allow"), runtime.requestPermission(approval("pending")))
        revoke("pending")
        assertNull(bridge.context("pending", "tool"))
        assertEquals(AcpPermissionOutcome.Selected("allow"), runtime.requestPermission(approval("running")))
        val lifetime = requireNotNull(bridge.context("running", "tool")?.lifetime)
        revoke("running", "different-turn")
        assertTrue(lifetime.isActive)
        revoke("running")
        assertFalse(lifetime.isActive)
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
        runtime.close()
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
    fun `plan review always waits for the users selected option`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        val turn = session.sender().send(prompt("plan"))
        val request = AcpPermissionRequest(
            session.ref.nativeId,
            buildJsonObject {
                put("title", "ExitPlanMode")
                put(
                    "content",
                    Json.parseToJsonElement("""[{"type":"content","content":{"type":"text","text":"Plan body"}}]"""),
                )
                put(
                    "_meta",
                    buildJsonObject {
                        put("heartbeatTurn", connection.turnToken)
                        put("heartbeatPlanReview", true)
                    },
                )
            },
            listOf(
                AcpPermissionOption("plan_opt_0", "First", "allow_once"),
                AcpPermissionOption("plan_opt_1", "Second", "allow_once"),
                AcpPermissionOption("plan_revise", "Revise", "reject_once"),
            ),
        )
        val answer = async { runtime.requestPermission(request) }
        testScheduler.runCurrent()
        val pending = assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value).requests.single()
        assertEquals("Plan body", pending.title)
        assertEquals(listOf("First", "Second", "Revise"), pending.options.map { it.title })
        session.permissions().respond(PermissionDecision(turn, pending.id, PermissionOptionId("plan_revise")))
        assertEquals(AcpPermissionOutcome.Selected("plan_revise"), answer.await())
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
                """{"configOptions":[{"id":"model","category":"model",
                    "options":[{"value":"kimi-code/kimi-for-coding"}]}]}""",
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
    fun `permission retains exact action details while waiting for matching user decision`() = runTest {
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
        val waiting = assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value)
        assertEquals(TEST_APPROVAL_DESCRIPTION, waiting.requests.single().description)
        assertEquals("Kimi CLI requests permission", waiting.requests.single().title)
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
                    {"id":"model","category":"model","options":[{"value":"kimi-code/kimi-for-coding"}]},
                    {"id":"thinking","category":"thought_level","options":[{"value":"low"},{"value":"max"}]},
                    {"id":"mode","options":[]}
                ]}""",
            ).jsonObject
        }
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        connection.beforeConfigResponse = {
            connection.update(
                """{"sessionUpdate":"config_option_update","configOptions":[
                    {"id":"model","currentValue":"kimi-code/kimi-for-coding"}]}""",
            )
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
            metadata = Json.parseToJsonElement(
                """{"configOptions":[{"id":"model","category":"model",
                    "options":[{"value":"kimi-code/kimi-for-coding"}]}]}""",
            ).jsonObject
            configFailure = AcpException.Remote(-32602, "Invalid params")
        }
        val deleteCapability = JsonObject(mapOf("delete" to JsonObject(emptyMap())))
        val runtime = runtime(connection, this, JsonObject(mapOf("sessionCapabilities" to deleteCapability)))
        val failure = assertFailsWith<EngineException> { runtime.create(CreateSessionRequest(target)) }
        assertIs<EngineFailure.Transport>(failure.failure)
        connection.configFailure = null
        val session = runtime.create(CreateSessionRequest(target))
        connection.prompt.complete(AcpPromptResult("end_turn"))
        session.sender().send(prompt("next"))
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `admitted runtime exposes the stamp and the observed handshake facts`() = runTest {
        val stamp = EngineCompatibilityStamp(
            KimiEngine.Id,
            "heartbeat",
            "connector",
            "2.4.0-hb7",
            EngineProtocolIdentity.Negotiated("acp", 1),
            0,
            0,
            "darwin-arm64",
            "a".repeat(64),
        )
        val runtime = runtime(
            FakeConnection(),
            this,
            agentInfo = AcpImplementation("Kimi Code CLI", "2.4.0-hb7"),
            compatibilityStamp = stamp,
            selectedExecutable = "/data/kimi",
        )
        assertEquals(stamp, runtime.compatibilityStamp)
        assertEquals("2.4.0-hb7", runtime.observedRuntimeVersion)
        assertEquals(EngineProtocolIdentity.Negotiated("acp", 1), runtime.observedProtocol)
        assertEquals("/data/kimi", runtime.selectedExecutable)
        runtime.close()
    }

    private fun prompt(id: String) = PromptRequest(RequestId(id), listOf(ContentPart.Text("Hello")))

    private fun runtime(
        connection: FakeConnection,
        scope: CoroutineScope,
        capabilities: JsonObject = JsonObject(emptyMap()),
        tools: ProfileAgentTools = TestAgentTools,
        bridge: AgentToolBridge = UnavailableAgentToolBridge,
        agentInfo: AcpImplementation? = null,
        compatibilityStamp: EngineCompatibilityStamp? = null,
        selectedExecutable: String? = null,
    ): KimiRuntime {
        val runtime = KimiRuntime(
            RuntimeIdentity(KimiEngine.Id, KimiEngine.AuthSource, AuthRevision.Known("one")),
            connection,
            CoroutineScope(scope.coroutineContext + Job((scope as TestScope).backgroundScope.coroutineContext[Job])),
            AcpInitialization(1, capabilities, agentInfo = agentInfo),
            directory = { "/workspace" },
            toggles = TestToggles(),
            tools = tools,
            bridge = bridge,
            compatibilityStamp = compatibilityStamp,
            selectedExecutable = selectedExecutable,
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
    var newSessionBarrier: CompletableDeferred<Unit>? = null
    var loadSessionBarrier: CompletableDeferred<Unit>? = null
    var newSessions = 0
    var loadedSessions = 0
    var deletedSession: String? = null
    var closedSession: String? = null
    var deleteSessionFailure: Exception? = null
    var deleteSessionBarrier: CompletableDeferred<Unit>? = null
    var prompt = CompletableDeferred<AcpPromptResult>()
    var servers: List<JsonObject> = emptyList()
    var turnToken = ""
    var cancelled: String? = null
    var submissionBarrier: CompletableDeferred<Unit>? = null
    var cancellationBarrier: CompletableDeferred<Unit>? = null
    var metadata = Json.parseToJsonElement(
        """{"configOptions":[{"id":"model","category":"model",
            "options":[{"value":"kimi-code/kimi-for-coding"}]}]}""",
    ).jsonObject
    var content = listOf<JsonObject>()
    var configFailure: Exception? = null
    var reportedModel: String? = null
    var reportedModelAfterMode: String? = null
    var reportedModelAfterThinking: String? = null
    var reportedThinking: String? = null
    var beforeConfigResponse: (suspend () -> Unit)? = null
    val configuration = mutableMapOf<String, String>()
    lateinit var runtime: KimiRuntime

    override suspend fun initialize(client: AcpImplementation) = initialization?.await() ?: AcpInitialization(1)
    override suspend fun authenticate(methodId: String) = Unit
    override suspend fun newSession(cwd: String): AcpSession {
        newSessions++
        newSessionBarrier?.await()
        return AcpSession("native", metadata)
    }
    override suspend fun deleteSession(sessionId: String) {
        deleteSessionBarrier?.await()
        deleteSessionFailure?.let { throw it }
        deletedSession = sessionId
    }
    override suspend fun closeSession(sessionId: String) {
        closedSession = sessionId
    }
    override suspend fun newSession(cwd: String, servers: List<JsonObject>): AcpSession {
        this.servers = servers
        return newSession(cwd)
    }
    override suspend fun loadSession(sessionId: String, cwd: String, servers: List<JsonObject>): AcpSession {
        this.servers = servers
        return loadSession(sessionId, cwd)
    }
    override suspend fun loadSession(sessionId: String, cwd: String): AcpSession {
        loadedSessions++
        loadSessionBarrier?.await()
        return AcpSession(sessionId, metadata)
    }
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

    override suspend fun setConfigOptionObserved(sessionId: String, optionId: String, value: String): JsonObject {
        setConfigOption(sessionId, optionId, value)
        return buildJsonObject {
            put(
                "configOptions",
                kotlinx.serialization.json.buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", "model")
                            val actual = when (optionId) {
                                "mode" -> reportedModelAfterMode
                                "thinking" -> reportedModelAfterThinking
                                else -> reportedModel
                            }
                            put("currentValue", actual ?: configuration["model"] ?: value)
                        },
                    )
                    configuration["thinking"]?.let { thinking ->
                        add(
                            buildJsonObject {
                                put("id", "thinking")
                                put("currentValue", reportedThinking ?: thinking)
                            },
                        )
                    }
                },
            )
        }
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

    override suspend fun authorizeNative(context: AgentToolContext, call: NativeToolCall): NativeVerdict {
        val approval = AgentToolApproval(call.name, "Kimi CLI requests permission", TEST_APPROVAL_DESCRIPTION)
        return if (context.permissions.request(approval)) {
            NativeVerdict.Allow
        } else {
            NativeVerdict.Deny("Denied")
        }
    }
}

/** A generic title must not hide the exact command, arguments or working directory from the decision maker. */
private const val TEST_APPROVAL_DESCRIPTION =
    """{"command":"git diff --check","arguments":{"command":"git diff --check"},""" +
        "\"workingDirectory\":\"/workspace/app\"}"
