package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpConnection
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpImplementation
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpInitialization
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPromptResult
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSession
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTrees
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeepSeekRecoveryTest {
    @Test
    fun `hanging tool is isolated from a different model and Stop retires only its process`() = runTest {
        val h = groupHarness()
        val a = h.group.create(CreateSessionRequest(h.target("model-a")))
        val turn = a.send("a")
        val b = h.group.create(CreateSessionRequest(h.target("model-b")))
        b.send("b")
        assertEquals(2, h.connections.size)

        a.feature(CancelsTurns).cancel(turn)
        runCurrent()
        advanceTimeBy(DSH_CANCEL_TIMEOUT_MS)
        runCurrent()

        assertRetired(a)
        assertTrue(h.connections[0].closed.isCompleted)
        assertFalse(h.connections[1].closed.isCompleted)
        assertIs<ActiveSessionState.Running>(b.state.value)
        h.connections[1].finish(b.ref.nativeId, "end_turn")
        runCurrent()
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(b.state.value).lastTurn?.outcome)
        b.send("b-next")
        assertFalse(h.group.isClosed)
    }

    @Test
    fun `stalled session opening does not delay a different session and shutdown closes both processes`() = runTest {
        val h = groupHarness()
        h.stallFirstOpen = true
        val opening = async {
            assertFailsWith<CancellationException> {
                h.group.create(
                    CreateSessionRequest(h.target("a")),
                )
            }
        }
        runCurrent()
        val b = h.group.create(CreateSessionRequest(h.target("b")))
        b.send("b")
        assertIs<ActiveSessionState.Running>(b.state.value)
        h.group.close()
        runCurrent()
        assertFailsWith<CancellationException> { opening.await() }
        assertTrue(h.connections.all { it.closed.isCompleted })
        assertTrue(h.group.isClosed)
    }

    @Test
    fun `shutdown cancels and awaits a process still initializing`() = runTest {
        val h = groupHarness()
        h.stallFactory = true
        val opening = async { h.group.create(CreateSessionRequest(h.target("a"))) }
        runCurrent()
        assertTrue(h.factoryStarted)
        h.group.close()
        assertTrue(h.factoryCancelled)
        assertFailsWith<CancellationException> { opening.await() }
    }

    @Test
    fun `reattachment to the same native session shares its process until both leases close`() = runTest {
        val h = groupHarness()
        val a = h.group.create(CreateSessionRequest(h.target("a")))
        val second = h.group.attach(a.ref, ResumeSessionRequest(h.target("a")))
        assertEquals(1, h.connections.size)
        a.close()
        assertFalse(h.connections.single().closed.isCompleted)
        second.close()
        runCurrent()
        assertTrue(h.connections.single().closed.isCompleted)
    }

    @Test
    fun `reattaching while the last lease retires waits for old process closure`() = runTest {
        val h = groupHarness()
        val a = h.group.create(CreateSessionRequest(h.target("a")))
        val gate = CompletableDeferred<Unit>()
        h.connections.single().closeGate = gate
        val closing = async { a.close() }
        runCurrent()
        val resumed = async { h.group.attach(a.ref, ResumeSessionRequest(h.target("a"))) }
        runCurrent()
        assertFalse(resumed.isCompleted)
        assertEquals(1, h.connections.size)
        gate.complete(Unit)
        runCurrent()
        closing.await()
        assertEquals(a.ref, resumed.await().ref)
        assertEquals(2, h.connections.size)
        assertTrue(h.connections.first().closed.isCompleted)
    }

    @Test
    fun `failure closing one process does not leave the other process running`() = runTest {
        val h = groupHarness()
        h.group.create(CreateSessionRequest(h.target("a")))
        h.group.create(CreateSessionRequest(h.target("b")))
        h.connections.first().failOnClose = true
        assertFailsWith<IllegalStateException> { h.group.close() }
        assertTrue(h.connections.all { it.closed.isCompleted })
    }

    @Test
    fun `last detached lease releases its process after the accepted turn settles`() = runTest {
        val h = groupHarness()
        val a = h.group.create(CreateSessionRequest(h.target("a")))
        a.send("request")
        val id = a.ref.nativeId
        a.close()
        assertFalse(h.connections.single().closed.isCompleted)
        h.connections.single().finish(id, "end_turn")
        runCurrent()
        assertTrue(h.connections.single().closed.isCompleted)
    }

    @Test
    fun `control deadlines do not interrupt a long running prompt`() = runTest {
        val h = harness()
        val session = h.open("model")
        session.send("long-running")

        advanceTimeBy(DSH_CONTROL_TIMEOUT_MS * 2)
        runCurrent()

        assertIs<ActiveSessionState.Running>(session.state.value)
        assertFalse(h.runtime.isClosed)
        assertFalse(h.connection.closed.isCompleted)
    }

    @Test
    fun `normal cancellation preserves another model on the shared runtime and disarms its watchdog`() = runTest {
        val h = harness()
        val a = h.open("a")
        val b = h.open("b")
        val turn = a.send("a")
        b.send("b")
        a.feature(CancelsTurns).cancel(turn)
        runCurrent()
        advanceTimeBy(DSH_CANCEL_TIMEOUT_MS - 1)
        h.connection.finish(a.ref.nativeId, "cancelled")
        runCurrent()
        assertEquals(TurnOutcome.Cancelled, assertIs<ActiveSessionState.Ready>(a.state.value).lastTurn?.outcome)
        a.send("next")
        advanceTimeBy(DSH_CANCEL_TIMEOUT_MS + 1)
        runCurrent()

        assertIs<ActiveSessionState.Running>(a.state.value)
        assertIs<ActiveSessionState.Running>(b.state.value)
        assertFalse(h.runtime.isClosed)
        assertFalse(h.connection.closed.isCompleted)
    }

    @Test
    fun `unacknowledged Stop retires shared runtime with Unknown outcomes and releases a neighboring open`() = runTest {
        val h = harness()
        val a = h.open("a")
        val b = h.open("b")
        val turn = a.send("a")
        b.send("b")
        val histories = listOf(a, b).map { it.feature(SessionHistory) }
        val checkpoints = histories.map { it.page(HistoryPageRequest()).checkpoint }
        h.connection.stalled = "open"
        val opening = async { assertFailsWith<EngineException> { h.open("c") } }
        runCurrent()

        a.feature(CancelsTurns).cancel(turn)
        runCurrent()
        advanceTimeBy(DSH_CANCEL_TIMEOUT_MS)
        runCurrent()
        opening.await()

        assertTrue(h.runtime.isClosed)
        assertTrue(h.connection.closed.isCompleted)
        assertRetired(a)
        assertRetired(b)
        for ((history, checkpoint) in histories.zip(checkpoints)) {
            assertEquals(
                TurnOutcome.Unknown,
                history.watch(checkpoint).filterIsInstance<SessionEvent.TurnFinished>().first().outcome,
            )
        }
        // A replacement connection can serve another model after the old runtime has been invalidated.
        assertIs<ActiveSessionState.Ready>(harness().open("replacement").state.value)
    }

    @Test
    fun `completion does not disarm a watchdog while cancel write stays blocked`() = runTest {
        val h = harness()
        val a = h.open("a")
        val turn = a.send("a")
        h.connection.stalled = "cancel"
        val cancelling = async { assertFailsWith<CancellationException> { a.feature(CancelsTurns).cancel(turn) } }
        runCurrent()
        h.connection.finish(a.ref.nativeId, "end_turn")
        runCurrent()
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(a.state.value).lastTurn?.outcome)
        advanceTimeBy(DSH_CANCEL_TIMEOUT_MS)
        runCurrent()
        cancelling.await()
        assertTrue(h.connection.closed.isCompleted)
    }

    @Test
    fun `Stop deadline also covers a blocked cancel notification write`() = runTest {
        val h = harness()
        val a = h.open("a")
        val turn = a.send("a")
        h.connection.stalled = "cancel"
        val cancelling = async { assertFailsWith<CancellationException> { a.feature(CancelsTurns).cancel(turn) } }
        runCurrent()

        advanceTimeBy(DSH_CANCEL_TIMEOUT_MS)
        runCurrent()
        cancelling.await()

        assertTrue(h.connection.closed.isCompleted)
        assertRetired(a)
    }

    @Test
    fun `abandoning the opening caller does not abandon its native control deadline`() = runTest {
        val h = harness()
        val existing = h.open("existing")
        existing.send("existing")
        h.connection.stalled = "open"
        val opening = async { h.open("new") }
        runCurrent()
        opening.cancelAndJoin()
        assertFalse(h.runtime.isClosed)

        advanceTimeBy(DSH_CONTROL_TIMEOUT_MS)
        runCurrent()

        assertTrue(h.runtime.isClosed)
        assertTrue(h.connection.closed.isCompleted)
        assertRetired(existing)
    }

    @Test
    fun `unresponsive open configure and resume retire the runtime instead of leaving control RPCs alive`() = runTest {
        for (operation in listOf("open", "configure", "resume")) {
            val h = harness()
            val existing = h.open("existing")
            existing.send("existing")
            h.connection.stalled = operation
            val control = async {
                assertFailsWith<EngineException> {
                    if (operation == "resume") {
                        h.runtime.resume(ResumeSessionRequest(h.target("resumed")), "stored")
                    } else {
                        h.open("new")
                    }
                }
            }
            runCurrent()
            advanceTimeBy(DSH_CONTROL_TIMEOUT_MS)
            runCurrent()

            assertEquals(EngineFailure.Transport(TransportFailureReason.Timeout), control.await().failure)
            assertTrue(h.connection.closed.isCompleted)
            assertRetired(existing)
        }
    }

    private fun assertRetired(session: ActiveSession) {
        val state = assertIs<ActiveSessionState.Unavailable>(session.state.value)
        assertEquals(TurnOutcome.Unknown, state.lastTurn?.outcome)
        assertEquals(null, state.activeTurn)
    }

    private fun TestScope.harness(): RecoveryHarness = RecoveryHarness(this)

    private fun TestScope.groupHarness(): RecoveryGroupHarness = RecoveryGroupHarness(this)

    private suspend fun ActiveSession.send(id: String): TurnId =
        feature(SendsPrompts).send(PromptRequest(RequestId(id), listOf(ContentPart.Text(id))))

    private fun <T : io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature> ActiveSession.feature(
        key: io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey<T>,
    ): T = assertIs<FeatureAccess.Available<T>>(features.resolve(key)).feature
}

private class RecoveryHarness(test: TestScope) {
    val connection = RecoveryConnection()
    private val lifetime = SupervisorJob(test.backgroundScope.coroutineContext[Job])
    val runtime = DeepSeekRuntime(
        RuntimeIdentity(DeepSeekEngine.Id, AuthSourceId("source"), AuthRevision.Unknown),
        connection,
        CoroutineScope(test.backgroundScope.coroutineContext + lifetime),
        ownedScope = lifetime,
        directory = { "C:\\workspace" },
    )

    init {
        connection.submitted = runtime::onPromptSubmitted
    }

    fun target(model: String) = EngineTarget(DeepSeekEngine.Id, EngineBindingId("binding"), ModelId(model))
    suspend fun open(model: String): ActiveSession = runtime.create(CreateSessionRequest(target(model)))
}

/** Models a shared ACP process which can stop answering control messages independently of prompt ownership. */
private class RecoveryConnection(private val prefix: String = "session") : AcpConnection {
    val closed = CompletableDeferred<Unit>()
    var stalled: String? = null
    var acknowledgeCancel = false
    var closeGate: CompletableDeferred<Unit>? = null
    var failOnClose = false
    var submitted: (String) -> Unit = {}
    private var next = 0
    private val prompts = mutableMapOf<String, CompletableDeferred<AcpPromptResult>>()
    private val metadata = Json.parseToJsonElement(
        """{"configOptions":[{"id":"model","category":"model","options":[]}]}""",
    ) as JsonObject

    override suspend fun initialize(client: AcpImplementation) = AcpInitialization(
        1,
        JsonObject(emptyMap()),
        emptyList(),
    )
    override suspend fun authenticate(methodId: String) = Unit
    override suspend fun newSession(cwd: String): AcpSession {
        stall("open")
        return AcpSession("$prefix-${++next}", metadata)
    }
    override suspend fun loadSession(sessionId: String, cwd: String) = AcpSession(sessionId, metadata)
    override suspend fun resumeSession(sessionId: String, cwd: String): AcpSession {
        stall("resume")
        return AcpSession(sessionId, metadata)
    }
    override suspend fun setConfigOption(sessionId: String, optionId: String, value: String) = stall("configure")
    override suspend fun closeSession(sessionId: String) {
        closeGate?.await()
    }
    override suspend fun prompt(sessionId: String, content: List<JsonObject>): AcpPromptResult {
        val response = CompletableDeferred<AcpPromptResult>().also { prompts[sessionId] = it }
        submitted(sessionId)
        return response.await()
    }
    fun finish(sessionId: String, reason: String) {
        prompts.getValue(sessionId).complete(AcpPromptResult(reason))
    }

    override suspend fun cancel(sessionId: String) {
        stall("cancel")
        if (acknowledgeCancel) prompts.getValue(sessionId).complete(AcpPromptResult("cancelled"))
    }
    override suspend fun close() {
        closed.complete(Unit)
        prompts.values.forEach { it.completeExceptionally(AcpException.Disconnected()) }
        check(!failOnClose) { "Simulated transport close failure" }
    }
    private suspend fun stall(operation: String) {
        if (stalled != operation) return
        closed.await()
        throw AcpException.Disconnected()
    }
}

private class RecoveryGroupHarness(private val test: TestScope) {
    val connections = mutableListOf<RecoveryConnection>()
    var stallFirstOpen = false
    var stallFactory = false
    var factoryStarted = false
    var factoryCancelled = false
    private val factoryGate = CompletableDeferred<Unit>()
    private val identity = RuntimeIdentity(DeepSeekEngine.Id, AuthSourceId("source"), AuthRevision.Unknown)
    private val trees = object : SessionTrees {
        override fun observe(root: SessionRef, access: SessionTreeAccess): Flow<SessionTreeSnapshot> = emptyFlow()
        override suspend fun history(root: SessionRef, ref: SessionRef, access: SessionTreeAccess): SessionHistory =
            error("Not used")
    }
    val group = DeepSeekRuntimeGroup(identity, trees) { retired ->
        if (stallFactory) {
            factoryStarted = true
            try {
                factoryGate.await()
            } finally {
                factoryCancelled = true
            }
        }
        val connection = RecoveryConnection("child-${connections.size}")
        if (stallFirstOpen && connections.isEmpty()) connection.stalled = "open"
        connections += connection
        val lifetime = SupervisorJob(test.backgroundScope.coroutineContext[Job])
        DeepSeekRuntime(
            identity,
            connection,
            CoroutineScope(test.backgroundScope.coroutineContext + lifetime),
            directory = { "C:\\workspace" },
            ownedScope = lifetime,
            isSingleSession = true,
            onRetired = retired,
        ).also { connection.submitted = it::onPromptSubmitted }
    }
    fun target(model: String) = EngineTarget(DeepSeekEngine.Id, EngineBindingId("binding"), ModelId(model))
}
