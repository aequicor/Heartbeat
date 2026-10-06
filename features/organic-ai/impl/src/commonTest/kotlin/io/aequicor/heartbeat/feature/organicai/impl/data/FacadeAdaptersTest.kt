package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.organicai.api.OrganismSession
import io.aequicor.heartbeat.feature.organicai.api.ReleaseMode
import io.aequicor.heartbeat.feature.organicai.impl.C1
import io.aequicor.heartbeat.feature.organicai.impl.ORGANISM
import io.aequicor.heartbeat.feature.organicai.impl.TARGET
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellKey
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellRoute
import io.aequicor.heartbeat.feature.organicai.impl.message
import io.aequicor.heartbeat.feature.organicai.impl.session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class FacadeAdaptersTest {
    private val key = CellKey(ORGANISM, C1)
    private val route = CellRoute(TARGET, WorkspaceRef("project"), TrustLevel.AutoEdits)

    @Test
    fun `a new cell session carries the detached tools, its project and trust`() = runTest {
        val native = FakeSession(session("new"))
        val facade = FakeFacade(ArrayDeque(listOf(native)))
        val cells = FacadeCellSessions(facade)
        val handle = cells.open(key, route, existing = null)
        assertSame(handle.session, cells.open(key, route, existing = null).session)
        assertEquals(1, facade.creations.size)
        with(facade.creations.single()) {
            assertTrue(areDetachedToolsEnabled)
            assertEquals(WorkspaceRef("project"), workspace)
            assertEquals(TARGET, target)
        }
        val image = ResourceRef("attachment:image", "image/png")
        val document = ResourceRef("attachment:document", "text/plain")
        handle.submit(RequestId("r1"), "work", TrustLevel.AutoEdits, listOf(image, document))
        assertEquals(TrustLevel.AutoEdits, native.prompts.single().trust)
        assertEquals(
            listOf(ContentPart.Text("work"), ContentPart.Image(image), ContentPart.Resource(document)),
            native.prompts.single().parts,
        )
    }

    @Test
    fun `late retirement cannot close the handle of a resumed cell`() = runTest {
        val native = FakeSession(session("continued"))
        val cells = FacadeCellSessions(FakeFacade(ArrayDeque(listOf(native))))
        cells.open(key, route, existing = null, generation = 1)
        val resumed = cells.open(key, route, existing = native.ref, generation = 2)
        cells.release(key, native.ref, ReleaseMode.Retire, generation = 1)
        assertEquals(0, native.closes)
        assertFailsWith<EngineException> { cells.open(key, route, native.ref, generation = 1) }
        resumed.submit(RequestId("continued"), "check the remaining work", TrustLevel.AutoEdits)
        assertEquals(1, native.prompts.size)
        cells.release(key, native.ref, ReleaseMode.Retire, generation = 2)
        assertEquals(1, native.closes)
    }

    @Test
    fun `a slow resumption cannot replace or cancel a newer turn's handle`() = runTest {
        val old = FakeSession(session("continued")).apply {
            resumedAs = ActiveSessionState.Running(Turn(TurnId("active"), RequestId("new"), TARGET))
        }
        val newer = FakeSession(old.ref).apply { resumedAs = old.resumedAs }
        val stored = mutableMapOf(old.ref to old)
        val facade = FakeFacade(stored = stored)
        val blocked = CompletableDeferred<Unit>()
        facade.onResume = { if (facade.resumptions.size == 1) blocked.await() }
        val cells = FacadeCellSessions(facade)
        val stale = async { assertFailsWith<EngineException> { cells.open(key, route, old.ref, generation = 1) } }
        runCurrent()
        stored[old.ref] = newer
        cells.open(key, route, old.ref, generation = 2)
        blocked.complete(Unit)
        stale.await()
        assertEquals(1, old.closes)
        assertTrue(old.cancelled.isEmpty())
        assertFalse(old.isArchived)
        assertEquals(0, newer.closes)
        cells.open(key, route, old.ref, generation = 2).submit(RequestId("new"), "work", null)
        assertEquals(1, newer.prompts.size)
        assertEquals(2, facade.resumptions.size)
    }

    @Test
    fun `a completed cell can reopen its retired session and still be lysed`() = runTest {
        val native = FakeSession(session("continued"))
        val facade = FakeFacade(ArrayDeque(listOf(native)), stored = mapOf(native.ref to native))
        val cells = FacadeCellSessions(facade)
        cells.open(key, route, existing = null, generation = 1)
        cells.release(key, native.ref, ReleaseMode.Retire, generation = 1)
        cells.open(key, route, native.ref, generation = 2)
        assertEquals(1, facade.resumptions.size)
        cells.release(key, native.ref, ReleaseMode.Lyse, generation = 2)
        assertTrue(native.isArchived)
        assertFailsWith<EngineException> { cells.open(key, route, native.ref, generation = 3) }
    }

    @Test
    fun `a stored cell session is resumed with the detached tools`() = runTest {
        val stored = FakeSession(session("stored"))
        val facade = FakeFacade(stored = mapOf(stored.ref to stored))
        FacadeCellSessions(facade).open(key, route, existing = stored.ref)
        assertTrue(facade.resumptions.single().areDetachedToolsEnabled)
        assertEquals(0, facade.creations.size)
    }

    @Test
    fun `trust is withheld from a session that does not apply trust levels`() = runTest {
        val native = FakeSession(session("plain"), hasTrust = false)
        native.submit(RequestId("r1"), "work", TrustLevel.Full)
        assertNull(native.prompts.single().trust)
    }

    @Test
    fun `a turn reports its pending permission requests until it ends`() = runTest {
        val native = FakeSession(session("n")).apply { finish = null }
        val turn = native.submit(RequestId("r1"), "work", null)
        val reported = mutableListOf<List<PermissionRequest>>()
        val outcome = async { native.awaitTurn(turn) { reported += it } }
        runCurrent()
        val running = Turn(turn, RequestId("r1"), TARGET)
        native.state.value = ActiveSessionState.AwaitingUserAction(running, listOf(permission(turn)))
        runCurrent()
        native.state.value = ActiveSessionState.Running(running)
        runCurrent()
        native.end(turn, TurnOutcome.Completed)
        assertEquals(TurnOutcome.Completed, outcome.await())
        assertEquals(listOf(listOf(permission(turn)), emptyList()), reported)
    }

    @Test
    fun `an unavailable session that lost the turn ends the wait as unknown`() = runTest {
        val native = FakeSession(session("n")).apply { finish = null }
        val turn = native.submit(RequestId("r1"), "work", null)
        native.state.value = ActiveSessionState.Unavailable(EngineFailure.Unknown())
        assertEquals(TurnOutcome.Unknown, native.awaitTurn(turn) {})
        native.state.value = ActiveSessionState.Closed
        assertTrue(native.awaitTurn(turn) {} is TurnOutcome.Failed)
    }

    @Test
    fun `a refused submission fails and leaves the session ready for the next turn`() = runTest {
        val native = FakeSession(session("n")).apply {
            sendFailure = EngineFailure.Request(RequestFailureReason.Invalid, RequestId("r1"))
            synchronized = ActiveSessionState.Ready()
        }
        assertFailsWith<EngineException> { native.submit(RequestId("r1"), "work", null) }
        assertEquals(1, native.synchronizations)
        assertEquals(ActiveSessionState.Ready(), native.state.value)
    }

    @Test
    fun `a refusal the synchronized session runs anyway is followed`() = runTest {
        val native = FakeSession(session("n")).apply {
            sendFailure = EngineFailure.Transport(TransportFailureReason.Timeout)
            synchronized = ActiveSessionState.Running(Turn(TurnId("turn-1"), RequestId("r1"), TARGET))
        }
        assertEquals(TurnId("turn-1"), native.submit(RequestId("r1"), "work", null))
    }

    @Test
    fun `an ambiguous submission follows the turn under the id the synchronized session shows`() = runTest {
        val native = FakeSession(session("n")).apply {
            sendFailure = EngineFailure.Request(RequestFailureReason.OutcomeUnknown, RequestId("r1"))
            synchronized = ActiveSessionState.Running(Turn(TurnId("native-1"), RequestId("r1"), TARGET))
        }
        assertEquals(TurnId("native-1"), native.submit(RequestId("r1"), "work", null))
    }

    @Test
    fun `an unknown outcome of another request is not followed`() = runTest {
        val native = FakeSession(session("n")).apply {
            sendFailure = EngineFailure.Request(RequestFailureReason.OutcomeUnknown, RequestId("r0"))
        }
        assertFailsWith<EngineException> { native.submit(RequestId("r1"), "work", null) }
    }

    @Test
    fun `an ambiguous submission follows the turn the engine may have started`() = runTest {
        val native = FakeSession(session("n")).apply {
            sendFailure = EngineFailure.Request(RequestFailureReason.OutcomeUnknown, RequestId("r1"))
        }
        assertEquals(TurnId("turn-1"), native.submit(RequestId("r1"), "work", null))
        assertEquals(1, native.synchronizations)
    }

    @Test
    fun `every unavailability during a turn is synchronized`() = runTest {
        val native = FakeSession(session("n")).apply { finish = null }
        val turn = native.submit(RequestId("r1"), "work", null)
        val running = Turn(turn, RequestId("r1"), TARGET)
        val outcome = async { native.awaitTurn(turn) {} }
        runCurrent()
        native.synchronized = ActiveSessionState.Running(running)
        native.state.value = ActiveSessionState.Unavailable(EngineFailure.Unknown(), running)
        runCurrent()
        native.synchronized = ActiveSessionState.Ready(running.copy(outcome = TurnOutcome.Completed))
        native.state.value = ActiveSessionState.Unavailable(EngineFailure.Unknown(), running)
        assertEquals(TurnOutcome.Completed, outcome.await())
        assertEquals(2, native.synchronizations)
    }

    @Test
    fun `a session that stays unavailable fails the turn instead of waiting forever`() = runTest {
        val native = FakeSession(session("n")).apply { finish = null }
        val turn = native.submit(RequestId("r1"), "work", null)
        val failure = EngineFailure.Unknown()
        native.state.value = ActiveSessionState.Unavailable(failure, Turn(turn, RequestId("r1"), TARGET))
        assertEquals(TurnOutcome.Failed(failure), native.awaitTurn(turn) {})
        assertEquals(8, native.synchronizations)
    }

    @Test
    fun `stopping closes the handle even when cancelling fails`() = runTest {
        val native = FakeSession(session("n")).apply {
            finish = null
            cancelFailure = IllegalStateException("cancel failed")
        }
        native.submit(RequestId("r1"), "work", null)
        assertFailsWith<IllegalStateException> { native.stop(1.seconds) }
        assertEquals(1, native.closes)
    }

    @Test
    fun `a cancelled sleep still stops and closes every handle`() = runTest {
        val native = FakeSession(session("new")).apply {
            finish = null
            ignoresCancel = true
        }
        val cells = FacadeCellSessions(FakeFacade(ArrayDeque(listOf(native))))
        cells.open(key, route, null).submit(RequestId("r1"), "work", null)
        val sleeping = launch { cells.releaseAll() }
        runCurrent()
        sleeping.cancel()
        advanceUntilIdle()
        assertEquals(1, native.cancelled.size)
        assertEquals(1, native.closes)
    }

    @Test
    fun `a turn the ready session no longer reports ends the wait as unknown`() = runTest {
        val native = FakeSession(session("n")).apply { finish = null }
        val turn = native.submit(RequestId("r1"), "work", null)
        native.state.value = ActiveSessionState.Ready(Turn(TurnId("other"), null, TARGET, TurnOutcome.Completed))
        assertEquals(TurnOutcome.Unknown, native.awaitTurn(turn) {})
    }

    @Test
    fun `the answer of a turn is read from history`() = runTest {
        val native = FakeSession(session("new"))
        val handle = FacadeCellSessions(FakeFacade(ArrayDeque(listOf(native)))).open(key, route, null)
        // History carries the engine's own turn ids, not the ids the handle gave its submissions.
        native.items = listOf(
            message(MessageRole.User, "work", turn = "native-1", position = 0),
            message(MessageRole.Assistant, "done", turn = "native-1", position = 1),
        )
        assertEquals("done", handle.answer(TurnId("turn-1")))
        // A turn the engine was seen to take keeps the answer after its prompt even when its end is unconfirmed.
        assertEquals("done", handle.answer(TurnId("turn-1"), isUnconfirmed = true))
    }

    @Test
    fun `an unconfirmed turn followed only from memory takes no answer of an earlier turn`() = runTest {
        val native = FakeSession(session("new")).apply {
            sendFailure = EngineFailure.Request(RequestFailureReason.OutcomeUnknown, RequestId("r2"))
        }
        val handle = FacadeCellSessions(FakeFacade(ArrayDeque(listOf(native)))).open(key, route, null)
        val turn = handle.submit(RequestId("r2"), "more", null)
        native.items = listOf(
            message(MessageRole.User, "work", turn = "native-1", position = 0),
            message(MessageRole.Assistant, "old answer", turn = "native-1", position = 1),
        )
        assertNull(handle.answer(turn, isUnconfirmed = true))
        assertEquals("old answer", handle.answer(turn))
    }

    @Test
    fun `a lysed cell's turn is cancelled, its session archived and never reopened`() = runTest {
        val native = FakeSession(session("new")).apply { finish = null }
        val cells = FacadeCellSessions(FakeFacade(ArrayDeque(listOf(native))))
        val handle = cells.open(key, route, null)
        val turn = handle.submit(RequestId("r1"), "work", null)
        cells.release(key, native.ref, ReleaseMode.Lyse)
        assertEquals(listOf(turn), native.cancelled)
        assertEquals(1, native.closes)
        assertTrue(native.isArchived)
        assertFailsWith<EngineException> { cells.open(key, route, native.ref) }
    }

    @Test
    fun `a cell lysed while its session was created leaves no listed session`() = runTest {
        val native = FakeSession(session("new"))
        val facade = FakeFacade(ArrayDeque(listOf(native)))
        val cells = FacadeCellSessions(facade)
        facade.onCreate = { cells.release(key, null, ReleaseMode.Lyse) }
        assertFailsWith<EngineException> { cells.open(key, route, null) }
        assertEquals(1, native.closes)
        assertTrue(native.isArchived)
    }

    @Test
    fun `a cell lysed while its session was resumed has the recovered turn stopped`() = runTest {
        val running = Turn(TurnId("native"), RequestId("r0"), TARGET)
        val stored = FakeSession(session("stored")).apply { resumedAs = ActiveSessionState.Running(running) }
        val facade = FakeFacade(stored = mapOf(stored.ref to stored))
        val cells = FacadeCellSessions(facade)
        facade.onResume = { cells.release(key, stored.ref, ReleaseMode.Lyse) }
        assertFailsWith<EngineException> { cells.open(key, route, stored.ref) }
        assertEquals(listOf(running.id), stored.cancelled)
        assertEquals(1, stored.closes)
        assertTrue(stored.isArchived)
    }

    @Test
    fun `a session opened while the cells slept is stopped and not kept`() = runTest {
        val first = FakeSession(session("first"))
        val second = FakeSession(session("second"))
        val facade = FakeFacade(ArrayDeque(listOf(first, second)))
        val cells = FacadeCellSessions(facade)
        facade.onCreate = { cells.releaseAll() }
        assertFailsWith<EngineException> { cells.open(key, route, null) }
        assertEquals(1, first.closes)
        assertTrue(first.isArchived)
        facade.onCreate = {}
        assertSame(second.ref, cells.open(key, route, null).session)
    }

    @Test
    fun `an unreadable history fails the answer instead of reading as empty`() = runTest {
        val native = FakeSession(session("new")).apply { historyFailure = EngineFailure.Unknown() }
        val handle = FacadeCellSessions(FakeFacade(ArrayDeque(listOf(native)))).open(key, route, null)
        assertFailsWith<EngineException> { handle.answer(TurnId("turn-1")) }
    }

    @Test
    fun `a completed cell's session is closed but kept`() = runTest {
        val native = FakeSession(session("new"))
        val cells = FacadeCellSessions(FakeFacade(ArrayDeque(listOf(native))))
        cells.open(key, route, null)
        cells.release(key, native.ref, ReleaseMode.Retire)
        assertEquals(1, native.closes)
        assertFalse(native.isArchived)
    }

    @Test
    fun `sleep stops every running turn and decisions reach open sessions`() = runTest {
        val native = FakeSession(session("new")).apply { finish = null }
        val cells = FacadeCellSessions(FakeFacade(ArrayDeque(listOf(native))))
        val turn = cells.open(key, route, null).submit(RequestId("r1"), "work", null)
        val decision = PermissionDecision(turn, PermissionRequestId("p1"), PermissionOptionId("allow"))
        cells.respond(key, decision)
        assertEquals(listOf(decision), native.decisions)
        cells.releaseAll()
        assertEquals(listOf(turn), native.cancelled)
        assertEquals(1, native.closes)
        assertFalse(native.isArchived)
        assertFailsWith<EngineException> { cells.respond(key, decision) }
    }

    @Test
    fun `each judgement runs in its own tool-less session that is dismissed afterwards`() = runTest {
        val first = FakeSession(session("judge-1"))
        val second = FakeSession(session("judge-2"))
        val facade = FakeFacade(ArrayDeque(listOf(first, second)))
        val judges = FacadeJudgeSessions(facade)
        // History carries the engine's own turn ids.
        first.items = listOf(
            message(MessageRole.User, "dossier", turn = "native-1", position = 0),
            message(MessageRole.Assistant, "VERDICT spare", turn = "native-1", position = 1),
        )
        val sessions = mutableListOf<io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef>()
        assertEquals("VERDICT spare", judges.deliberate(TARGET, "dossier") { sessions += it })
        judges.deliberate(TARGET, "dossier") { sessions += it }
        assertEquals(listOf(first.ref, second.ref), sessions)
        facade.creations.forEach {
            assertFalse(it.areDetachedToolsEnabled)
            assertNull(it.workspace)
        }
        listOf(first, second).forEach {
            assertEquals(1, it.closes)
            assertTrue(it.isArchived)
        }
        assertEquals(TrustLevel.Ask, first.prompts.single().trust)
    }

    @Test
    fun `a failed judgement still dismisses its session`() = runTest {
        val judge = FakeSession(session("judge")).apply { finish = TurnOutcome.Failed(EngineFailure.Unknown()) }
        assertFailsWith<EngineException> {
            FacadeJudgeSessions(
                FakeFacade(ArrayDeque(listOf(judge))),
            ).deliberate(TARGET, "x") {}
        }
        assertEquals(1, judge.closes)
        assertTrue(judge.isArchived)
    }

    @Test
    fun `a judge that asks for permission is stopped`() = runTest {
        val judge = FakeSession(session("judge")).apply { finish = null }
        val facade = FakeFacade(ArrayDeque(listOf(judge)))
        val answer = async { FacadeJudgeSessions(facade).deliberate(TARGET, "x") {} }
        runCurrent()
        val turn = TurnId("turn-1")
        judge.state.value = ActiveSessionState.AwaitingUserAction(
            Turn(turn, judge.prompts.single().id, TARGET),
            listOf(permission(turn)),
        )
        assertEquals("", answer.await())
        assertEquals(listOf(turn), judge.cancelled)
    }

    @Test
    fun `an unconfirmed deliberation gives no verdict`() = runTest {
        val judge = FakeSession(session("judge")).apply {
            finish = TurnOutcome.Unknown
            items = listOf(message(MessageRole.Assistant, "VERDICT kill", turn = "turn-1"))
        }
        assertEquals("", FacadeJudgeSessions(FakeFacade(ArrayDeque(listOf(judge)))).deliberate(TARGET, "x") {})
        assertEquals(1, judge.closes)
    }

    @Test
    fun `transcripts are read from the stored session`() = runTest {
        val stored = FakeSession(session("stored")).apply { items = listOf(message(MessageRole.User, "hi")) }
        val facade = FakeFacade(stored = mapOf(stored.ref to stored))
        val items = FacadeTranscripts(facade).recent(OrganismSession(stored.ref, ResumeSessionRequest(TARGET)))
        assertEquals(stored.items, items)
        assertEquals(emptyList<ResumeSessionRequest>(), facade.resumptions)
    }

    @Test
    fun `transcripts of an engine without stored history are read from a reopened session`() = runTest {
        val stored = FakeSession(session("stored")).apply { items = listOf(message(MessageRole.User, "hi")) }
        val facade = FakeFacade(stored = mapOf(stored.ref to stored), hasStoredHistory = false)
        val reopening = ResumeSessionRequest(TARGET, areDetachedToolsEnabled = true)
        val items = FacadeTranscripts(facade).recent(OrganismSession(stored.ref, reopening))
        assertEquals(stored.items, items)
        assertEquals(listOf(reopening), facade.resumptions)
        assertEquals(1, stored.closes)
    }

    private fun permission(turn: TurnId) = PermissionRequest(
        PermissionRequestId("p1"),
        turn,
        "Run",
        listOf(PermissionOption(PermissionOptionId("allow"), "Allow")),
    )
}
