package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationMessage
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperModelOptions
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.HelperWorkBinding
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioContinuationLineageTest {
    private val fixture = NativeHelperFixture()
    private val root = RequestId("R")
    private val snapshot = StudioExecutionSnapshot(
        DefaultRunSettings,
        checkNotNull(fixture.record.target),
        "high",
        null,
        StudioHumanAuthority(),
    )
    private val source = StudioHelperReceipt(
        root,
        StudioHelperPhase.Terminal,
        "source",
        session = fixture.ref,
        turn = TurnId("native-R"),
        terminal = StudioHelperTerminal(fixture.ref, TurnId("native-R"), StudioHelperTerminalOutcome.Cancelled, ""),
        policy = StudioAttemptPolicy.Conversation,
        executionSnapshot = snapshot,
        causes = emptySet(),
    )

    @Test
    fun `continuation inherits exact policy and snapshot without copying native identity`() {
        val owned = StudioAttemptPolicy.OwnedHelperWork(
            HelperWorkBinding(ActionId("owner"), ActionId("reservation")),
            HelperModelOptions(),
        )
        for (policy in listOf(StudioAttemptPolicy.Conversation, owned)) {
            val parent = source.copy(policy = policy)
            val prepared = prepareContinuationReceipt(
                mapOf(root.value to parent),
                root,
                HelperPrompt(RequestId("C"), "go"),
            )
            val child = prepared.receipt
            assertTrue(prepared.isNew)
            assertEquals(policy, child.policy)
            assertEquals(snapshot, child.executionSnapshot)
            assertEquals(root, child.continuationOf)
            assertNull(child.session)
            assertNull(child.turn)
            assertNull(child.preparedSession)
            assertNull(child.submissionClaim)
            assertNull(child.nativeEntryClaim)
            assertNull(child.terminal)
        }
    }

    @Test
    fun `restored chain resolves R C and D to one leaf independently of newer human work`() {
        val c = child(mapOf("R" to source), root, "C").finished()
        val records = mapOf("R" to source, "C" to c)
        val d = child(records, c.request, "D")
        val journal = records + ("D" to d) + ("H" to source.copy(request = RequestId("H")))
        val restored = Json.decodeFromString<Map<String, StudioHelperReceipt>>(Json.encodeToString(journal))

        for (alias in listOf("R", "C", "D")) {
            assertEquals(d, continuationChain(restored, RequestId(alias)).last())
        }
        assertEquals(RequestId("H"), continuationChain(restored, RequestId("H")).single().request)
    }

    @Test
    fun `stop at a historical root fences the pending leaf and all future descendants`() {
        val c = child(mapOf("R" to source), root, "C")
        val records = mapOf("R" to source, "C" to c)
        val (stopped, actual) = stopContinuationChain(records, root, shouldStopNative = true)

        assertEquals(c.request, actual.request)
        assertEquals(StudioHelperPhase.NotSubmitted, actual.phase)
        assertEquals(source.terminal, resolveContinuation(stopped, root)?.result?.terminal)
        assertNull(resolveContinuation(stopped, c.request)?.result?.terminal)
        assertTrue(stopped.values.all { it.isContinuationFenced })
        assertFailsWith<CancellationException> { child(stopped, root, "D") }
        val (before, _) = stopContinuationChain(mapOf("R" to source), root, shouldStopNative = true)
        assertFailsWith<CancellationException> { child(before, root, "C") }
    }

    @Test
    fun `conversation result after restart retains terminal ancestor when unsent continuation was stopped`() = runTest {
        val c = child(mapOf("R" to source), root, "C")
        val (stopped, _) = stopContinuationChain(mapOf("R" to source, "C" to c), root, shouldStopNative = true)
        val stores = ChecklistTestStores()
        stores.keyValue(HelperAttemptsSpec).set(
            helperReceiptKey(HelperId(fixture.record.id)),
            Json.encodeToString(stopped),
        )
        val evidence = StudioConversationEvidence(
            StudioHelperAttempts(stores),
            lazy { error("Historical result must not launch work") },
            lazy { error("Historical result must not open a session") },
            NoResultDispatch,
        )

        val result = evidence.result(fixture.record, root)
        assertEquals(root, result?.request)
        assertEquals(TurnOutcome.Cancelled, result?.outcome)
        assertNull(evidence.result(fixture.record, c.request))
    }

    @Test
    fun `revoking future work does not interrupt an accepted descendant`() {
        val c = child(mapOf("R" to source), root, "C").copy(
            phase = StudioHelperPhase.Accepted,
            session = fixture.ref,
            turn = TurnId("native-C"),
        )
        val (fenced, actual) = stopContinuationChain(mapOf("R" to source, "C" to c), root, shouldStopNative = false)

        assertEquals(StudioHelperPhase.Accepted, actual.phase)
        assertFalse(actual.isStopRequested)
        assertTrue(actual.isContinuationRevoked)
        assertTrue(checkNotNull(fenced["R"]).isContinuationRevoked)
    }

    @Test
    fun `missing legacy unknown or unreserved work cannot gain continuation authority`() {
        val unknown = source.copy(terminal = source.terminal?.copy(outcome = StudioHelperTerminalOutcome.Unknown))
        val unreserved = source.copy(
            policy = StudioAttemptPolicy.OwnedHelperWork(
                HelperWorkBinding(ActionId("owner"), null),
                HelperModelOptions(),
            ),
        )
        for (invalid in listOf(
            source.copy(policy = null),
            source.copy(policy = StudioAttemptPolicy.UnknownService),
            source.copy(executionSnapshot = null),
            unknown,
            unreserved,
        )) {
            assertFailsWith<IllegalStateException> { child(mapOf("R" to invalid), root, "C") }
        }
        assertFailsWith<IllegalStateException> { child(emptyMap(), root, "C") }
    }

    @Test
    fun `same operation retries exact nonfinal NoSend but never an entered or accepted send`() {
        val prompt = HelperPrompt(RequestId("C"), "go")
        val c = child(mapOf("R" to source), root, "C")
        val rollback = c.copy(phase = StudioHelperPhase.NotSubmitted)
        val retried = prepareContinuationReceipt(mapOf("R" to source, "C" to rollback), root, prompt)
        assertTrue(retried.isNew)
        assertEquals(c, retried.receipt)
        val entered = c.copy(
            phase = StudioHelperPhase.Submitting,
            submissionClaim = "sender",
            nativeEntryClaim = "sender",
        )
        assertFalse(prepareContinuationReceipt(mapOf("R" to source, "C" to entered), root, prompt).isNew)
        val accepted = c.copy(phase = StudioHelperPhase.Accepted, session = fixture.ref, turn = TurnId("native-C"))
        assertFalse(prepareContinuationReceipt(mapOf("R" to source, "C" to accepted), root, prompt).isNew)
        assertFailsWith<CancellationException> {
            prepareContinuationReceipt(mapOf("R" to source, "C" to rollback.copy(isFinal = true)), root, prompt)
        }
    }

    @Test
    fun `branches cycles changed payload and replaced snapshots are rejected`() {
        val prompt = HelperPrompt(RequestId("C"), "go")
        val c = prepareContinuationReceipt(mapOf("R" to source), root, prompt).receipt
        val records = mapOf("R" to source, "C" to c)
        assertFalse(prepareContinuationReceipt(records, root, prompt).isNew)
        assertFailsWith<IllegalStateException> {
            prepareContinuationReceipt(
                records,
                root,
                prompt.copy(text = "other"),
            )
        }
        assertFailsWith<IllegalStateException> { child(records, root, "D") }
        val branch = c.copy(request = RequestId("D"))
        assertFailsWith<IllegalStateException> { continuationChain(records + ("D" to branch), root) }
        val cycle = source.copy(continuationOf = c.request)
        assertFailsWith<IllegalStateException> { continuationChain(records + ("R" to cycle), root) }
        val altered = c.copy(executionSnapshot = snapshot.copy(reasoningEffort = "low"))
        assertFailsWith<IllegalStateException> { continuationChain(records + ("C" to altered), root) }
    }

    private fun child(records: Map<String, StudioHelperReceipt>, source: RequestId, id: String): StudioHelperReceipt =
        prepareContinuationReceipt(records, source, HelperPrompt(RequestId(id), "go")).receipt

    private fun StudioHelperReceipt.finished(): StudioHelperReceipt = copy(
        phase = StudioHelperPhase.Terminal,
        session = fixture.ref,
        turn = TurnId("native-${request.value}"),
        terminal = StudioHelperTerminal(
            fixture.ref,
            TurnId("native-${request.value}"),
            StudioHelperTerminalOutcome.Cancelled,
            "",
        ),
    )
}

private object NoResultDispatch : StudioConversationDispatch {
    override suspend fun <T> owned(operation: suspend () -> T): T = error("No dispatch")
    override fun initialTarget(record: StudioChatRecord): EngineTarget? = error("No dispatch")
    override fun isPending(child: LogicalSessionId, request: RequestId): Boolean = false
    override suspend fun enqueue(
        child: LogicalSessionId,
        message: HostConversationMessage,
        cause: RequestInitiator,
        beforePrepare: suspend (Boolean) -> StudioChatRecord,
    ): Unit = error("No dispatch")
}
