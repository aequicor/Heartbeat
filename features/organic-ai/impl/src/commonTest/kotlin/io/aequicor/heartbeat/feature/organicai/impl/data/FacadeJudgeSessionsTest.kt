package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.organicai.api.CaseId
import io.aequicor.heartbeat.feature.organicai.impl.TARGET
import io.aequicor.heartbeat.feature.organicai.impl.domain.MemoryNativeJournal
import io.aequicor.heartbeat.feature.organicai.impl.domain.judgeExecution
import io.aequicor.heartbeat.feature.organicai.impl.message
import io.aequicor.heartbeat.feature.organicai.impl.organism
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FacadeJudgeSessionsTest {
    private val native = MemoryNativeJournal()
    private fun execution(id: String) = organism()
        .judgeExecution(CaseId(id))

    @Test
    fun `a completed case restores its frozen verdict without another session or prompt`() = runTest {
        val judge = FakeSession(session("judge"))
        judge.items = listOf(message(MessageRole.Assistant, "VERDICT spare", turn = "turn-1"))
        val facade = FakeFacade(ArrayDeque(listOf(judge)))
        val judges = FacadeJudgeSessions(facade, native)
        val binding = execution("same-case")
        assertEquals("VERDICT spare", judges.deliberate(TARGET, "original dossier", null, binding) {})
        val refs = mutableListOf<SessionRef>()
        assertEquals("VERDICT spare", judges.deliberate(TARGET, "changed dossier", null, binding) { refs += it })
        assertEquals(listOf(judge.ref), refs)
        assertEquals(1, facade.creations.size)
        assertEquals(1, judge.prompts.size)
    }

    @Test
    fun `an unknown case remains unresolved instead of rerunning its dossier`() = runTest {
        val judge = FakeSession(session("judge")).apply { finish = TurnOutcome.Unknown }
        val facade = FakeFacade(ArrayDeque(listOf(judge)))
        val judges = FacadeJudgeSessions(facade, native)
        val binding = execution("same-case")
        assertEquals("", judges.deliberate(TARGET, "dossier", null, binding) {})
        assertFailsWith<EngineException> { judges.deliberate(TARGET, "new dossier", null, binding) {} }
        assertEquals(1, facade.creations.size)
        assertEquals(1, judge.prompts.size)
    }

    @Test
    fun `failed handle cleanup preserves the completed verdict`() = runTest {
        val judge = FakeSession(session("judge")).apply {
            closeFailure = IllegalStateException("close failed")
            items = listOf(message(MessageRole.Assistant, "VERDICT spare", turn = "turn-1"))
        }
        val judges = FacadeJudgeSessions(FakeFacade(ArrayDeque(listOf(judge))), native)
        assertEquals("VERDICT spare", judges.deliberate(TARGET, "dossier", null, execution("case")) {})
    }

    @Test
    fun `each judgement runs in its own tool-less session that is dismissed afterwards`() = runTest {
        val first = FakeSession(session("judge-1"))
        val second = FakeSession(session("judge-2"))
        val facade = FakeFacade(ArrayDeque(listOf(first, second)))
        val judges = FacadeJudgeSessions(facade, native)
        // History carries the engine's own turn ids.
        first.items = listOf(
            message(MessageRole.User, "dossier", turn = "native-1", position = 0),
            message(MessageRole.Assistant, "VERDICT spare", turn = "native-1", position = 1),
        )
        val sessions = mutableListOf<io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef>()
        assertEquals("VERDICT spare", judges.deliberate(TARGET, "dossier", null, execution("first")) { sessions += it })
        judges.deliberate(TARGET, "dossier", null, execution("second")) { sessions += it }
        assertEquals(listOf(first.ref, second.ref), sessions)
        facade.creations.forEach {
            assertFalse(it.areDetachedToolsEnabled)
            assertNull(it.workspace)
            // Judges stay in a clean room: never the organism's harnesses, hooks or working instructions.
            assertEquals(emptyMap(), it.preselected)
            assertFalse(it.areSessionHooksEnabled)
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
                native,
            ).deliberate(TARGET, "x", null, execution("first")) {}
        }
        assertEquals(1, judge.closes)
        assertTrue(judge.isArchived)
    }

    @Test
    fun `a judge that asks for permission is stopped`() = runTest {
        val judge = FakeSession(session("judge")).apply { finish = null }
        val facade = FakeFacade(ArrayDeque(listOf(judge)))
        val answer = async { FacadeJudgeSessions(facade, native).deliberate(TARGET, "x", null, execution("first")) {} }
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
        assertEquals(
            "",
            FacadeJudgeSessions(
                FakeFacade(ArrayDeque(listOf(judge))),
                native,
            ).deliberate(TARGET, "x", null, execution("first")) {},
        )
        assertEquals(1, judge.closes)
    }

    private fun permission(turn: TurnId) = PermissionRequest(
        PermissionRequestId("p1"),
        turn,
        "Run",
        listOf(PermissionOption(PermissionOptionId("allow"), "Allow")),
    )
}
