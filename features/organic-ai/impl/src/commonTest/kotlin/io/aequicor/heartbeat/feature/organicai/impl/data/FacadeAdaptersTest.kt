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
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.organicai.api.ReleaseMode
import io.aequicor.heartbeat.feature.organicai.impl.C1
import io.aequicor.heartbeat.feature.organicai.impl.ORGANISM
import io.aequicor.heartbeat.feature.organicai.impl.TARGET
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellKey
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellRoute
import io.aequicor.heartbeat.feature.organicai.impl.message
import io.aequicor.heartbeat.feature.organicai.impl.session
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
        handle.submit(RequestId("r1"), "work", TrustLevel.AutoEdits)
        assertEquals(TrustLevel.AutoEdits, native.prompts.single().trust)
        assertEquals(listOf(ContentPart.Text("work")), native.prompts.single().parts)
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
    fun `the answer of a turn is read from history`() = runTest {
        val native = FakeSession(session("new"))
        val handle = FacadeCellSessions(FakeFacade(ArrayDeque(listOf(native)))).open(key, route, null)
        native.items = listOf(message(MessageRole.Assistant, "done", turn = "turn-1"))
        assertEquals("done", handle.answer(TurnId("turn-1")))
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
        first.items = listOf(message(MessageRole.Assistant, "VERDICT spare", turn = "turn-1"))
        assertEquals("VERDICT spare", judges.deliberate(TARGET, "dossier"))
        judges.deliberate(TARGET, "dossier")
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
            ).deliberate(TARGET, "x")
        }
        assertEquals(1, judge.closes)
        assertTrue(judge.isArchived)
    }

    @Test
    fun `a judge that asks for permission is stopped`() = runTest {
        val judge = FakeSession(session("judge")).apply { finish = null }
        val facade = FakeFacade(ArrayDeque(listOf(judge)))
        val answer = async { FacadeJudgeSessions(facade).deliberate(TARGET, "x") }
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
    fun `transcripts are read from the stored session`() = runTest {
        val stored = FakeSession(session("stored")).apply { items = listOf(message(MessageRole.User, "hi")) }
        val items = FacadeTranscripts(FakeFacade(stored = mapOf(stored.ref to stored))).recent(stored.ref)
        assertEquals(stored.items, items)
    }

    private fun permission(turn: TurnId) = PermissionRequest(
        PermissionRequestId("p1"),
        turn,
        "Run",
        listOf(PermissionOption(PermissionOptionId("allow"), "Allow")),
    )
}
