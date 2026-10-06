package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperCancellation
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.HelperSubmission
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCreateRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class EngineStudioHelperChatsTest {
    @Test
    fun `accepted helper completes and its exact result survives host restart and next request`() = runTest {
        val fixture = HelperFixture(backgroundScope)
        val accepted = fixture.host.prompt(HelperChat, HelperInput)
        assertIs<HelperSubmission.Accepted>(accepted)
        assertNull(fixture.host.result(HelperChat, HelperInput.request))
        fixture.finish.complete(Unit)
        runCurrent()
        val expected = fixture.terminal(HelperInput.request).result(HelperInput.request)
        assertEquals(expected, fixture.host.result(HelperChat, HelperInput.request))
        val restored = fixture.restart()
        assertEquals(expected, restored.result(HelperChat, HelperInput.request))
        val next = HelperInput.copy(request = RequestId("R2"), isRecovery = true)
        assertIs<HelperSubmission.Accepted>(restored.prompt(HelperChat, next))
        assertEquals(expected, restored.result(HelperChat, HelperInput.request))
        assertEquals(2, fixture.sends)
        assertIs<HelperSubmission.Accepted>(restored.prompt(HelperChat, HelperInput))
        assertEquals(2, fixture.sends)
    }

    @Test
    fun `restored accepted request reconciles native terminal answer without resending`() = runTest {
        val fixture = HelperFixture(backgroundScope)
        fixture.attempts.prepare(HelperChat, HelperInput)
        fixture.attempts.begin(HelperChat, HelperInput.request)
        fixture.attempts.accepted(HelperChat, HelperInput.request, HelperSession, TurnId("R"))
        fixture.result = fixture.terminal(HelperInput.request)
        val restored = fixture.restart()
        assertEquals(fixture.result?.result(HelperInput.request), restored.result(HelperChat, HelperInput.request))
        fixture.result = null
        assertEquals("answer R", restored.result(HelperChat, HelperInput.request)?.answer)
        assertEquals(0, fixture.sends)
    }

    @Test
    fun `cancel before prompt and during preparation prevents delayed submission`() = runTest {
        val fixture = HelperFixture(backgroundScope)
        assertIs<HelperCancellation.NotSubmitted>(fixture.host.cancel(HelperChat, HelperInput.request))
        assertIs<HelperSubmission.NotSubmitted>(fixture.host.prompt(HelperChat, HelperInput))
        assertEquals(0, fixture.sends)
        fixture.beforeBegin = CompletableDeferred()
        val next = HelperInput.copy(request = RequestId("R2"))
        val prompt = async { fixture.host.prompt(HelperChat, next) }
        runCurrent()
        assertIs<HelperCancellation.NotSubmitted>(fixture.host.cancel(HelperChat, next.request))
        fixture.beforeBegin?.complete(Unit)
        assertIs<HelperSubmission.NotSubmitted>(prompt.await())
        assertEquals(0, fixture.sends)
    }

    @Test
    fun `cancel crosses native barrier while prompt waits for lost ACK`() = runTest {
        val fixture = HelperFixture(backgroundScope)
        fixture.beforeAck = CompletableDeferred()
        val prompt = async { fixture.host.prompt(HelperChat, HelperInput) }
        runCurrent()
        assertFalse(prompt.isCompleted)
        fixture.stopped = fixture.terminal(
            HelperInput.request,
        ).copy(outcome = StudioHelperTerminalOutcome.Unknown, answer = "")
        val cancelled = assertIs<HelperCancellation.Terminal>(fixture.host.cancel(HelperChat, HelperInput.request))
        assertEquals(fixture.stopped?.result(HelperInput.request), cancelled.result)
        assertEquals(1, fixture.stops)
        // A late native ACK may fill acceptance but cannot erase the terminal receipt.
        fixture.beforeAck?.complete(Unit)
        assertIs<HelperSubmission.Accepted>(prompt.await())
        assertEquals(cancelled.result, fixture.host.result(HelperChat, HelperInput.request))
        fixture.finish.complete(Unit)
    }

    @Test
    fun `unconfirmed cancellation retains unresolved request and blocks recovery Rprime`() = runTest {
        val fixture = HelperFixture(backgroundScope)
        fixture.host.prompt(HelperChat, HelperInput)
        assertIs<HelperCancellation.Unconfirmed>(fixture.host.cancel(HelperChat, HelperInput.request))
        assertEquals(HelperInput.request, fixture.attempts.unresolved(HelperChat))
        assertFailsWith<IllegalStateException> {
            fixture.host.prompt(HelperChat, HelperInput.copy(request = RequestId("R2"), isRecovery = true))
        }
        fixture.stopped = fixture.terminal(
            HelperInput.request,
        ).copy(outcome = StudioHelperTerminalOutcome.Unknown, answer = "")
        assertIs<HelperCancellation.Terminal>(fixture.host.cancel(HelperChat, HelperInput.request))
        assertIs<HelperSubmission.Accepted>(
            fixture.host.prompt(HelperChat, HelperInput.copy(request = RequestId("R2"))),
        )
        fixture.finish.complete(Unit)
    }

    @Test
    fun `caller cancellation does not cancel a profile owned native helper`() = runTest {
        val fixture = HelperFixture(backgroundScope)
        fixture.beforeAck = CompletableDeferred()
        val caller = async { fixture.host.prompt(HelperChat, HelperInput) }
        runCurrent()
        caller.cancelAndJoin()
        assertEquals(StudioHelperPhase.Submitting, fixture.attempts.receipt(HelperChat, HelperInput.request)?.phase)
        fixture.beforeAck?.complete(Unit)
        fixture.finish.complete(Unit)
        runCurrent()
        assertEquals("answer R", fixture.host.result(HelperChat, HelperInput.request)?.answer)
        assertEquals(1, fixture.sends)
    }

    @Test
    fun `duplicate prompt while ACK is pending shares one submission`() = runTest {
        val fixture = HelperFixture(backgroundScope)
        fixture.beforeAck = CompletableDeferred()
        val first = async { fixture.host.prompt(HelperChat, HelperInput) }
        val second = async { fixture.host.prompt(HelperChat, HelperInput) }
        runCurrent()
        assertEquals(1, fixture.sends)
        fixture.beforeAck?.complete(Unit)
        assertEquals(first.await(), second.await())
        fixture.finish.complete(Unit)
    }
}

private val HelperChat = HelperId("helper")
private val HelperInput = HelperPrompt(RequestId("R"), "private prompt")
private val HelperSession = SessionRef(EngineId("test"), SessionSourceId("local"), "native")

private class HelperFixture(private val scope: CoroutineScope) :
    StudioHelperRuns,
    StudioHelperChatRecords {
    private val stores = ChecklistTestStores()
    val attempts = StudioHelperAttempts(stores)
    val host = restart()
    val finish = CompletableDeferred<Unit>()
    var beforeBegin: CompletableDeferred<Unit>? = null
    var beforeAck: CompletableDeferred<Unit>? = null
    var sends = 0
    var stops = 0
    var result: StudioHelperTerminal? = null
    var stopped: StudioHelperTerminal? = null

    fun restart() = EngineStudioHelperChats(this, StudioHelperAttempts(stores), lazyOf(this), RunProfile(scope))
    fun terminal(request: RequestId) = StudioHelperTerminal(
        HelperSession,
        TurnId(request.value),
        StudioHelperTerminalOutcome.Completed,
        "answer ${request.value}",
    )

    override suspend fun runHelper(
        helper: HelperId,
        prompt: HelperPrompt,
        submission: StudioHelperSubmission,
        onAccepted: suspend () -> Unit,
    ) {
        beforeBegin?.await()
        submission.begin()
        sends++
        beforeAck?.await()
        attempts.accepted(helper, prompt.request, HelperSession, TurnId(prompt.request.value))
        onAccepted()
        finish.await()
        if (attempts.receipt(helper, prompt.request)?.terminal == null) {
            attempts.terminal(helper, prompt.request, terminal(prompt.request))
        }
    }

    override suspend fun helperTerminal(helper: HelperId, request: RequestId): StudioHelperTerminal? = result
    override suspend fun stopHelper(helper: HelperId, request: RequestId): StudioHelperTerminal? {
        stops++
        return stopped
    }

    override suspend fun createHelper(request: HelperCreateRequest): HelperId = error("Unused")
    override suspend fun helperMetadata(helper: HelperId) = HelperMetadata(
        helper,
        ActionId("wf_owner"),
        null,
        HelperSession,
        attempts.unresolved(helper),
    )

    override suspend fun isHelper(session: SessionRef): Boolean = session == HelperSession
}
