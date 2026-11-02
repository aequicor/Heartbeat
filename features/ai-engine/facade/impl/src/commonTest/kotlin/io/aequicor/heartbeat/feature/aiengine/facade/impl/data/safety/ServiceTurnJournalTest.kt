package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnStop
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionOperation
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ServiceTurnJournalTest {
    private val fixture = IsolatedTurnFixture()
    private val store = ServiceMemoryStore()
    private val journal = ServiceTurnJournal(store)
    private val work = ServiceTurn(fixture.target, RequestId("service-r"), "private source", 5.seconds, 100)

    @Test
    fun `exact prompt and native entry are authorized once only`() = runTest {
        val attempt = journal.begin(work, listOf("shell"))
        assertNull(attempt.binding.logicalSession)
        journal.opened(attempt.binding, fixture.ref)
        val prompt = journal.prepare(attempt.binding)
        assertTrue(journal.permitsPrompt(attempt.binding, fixture.ref, fixture.target, prompt))
        assertFalse(
            journal.permitsPrompt(
                attempt.binding,
                fixture.ref,
                fixture.target,
                prompt.copy(parts = listOf(ContentPart.Text("changed"))),
            ),
        )
        assertTrue(journal.permitsOperation(attempt.binding, SessionExecutionOperation.Submit))
        for (operation in listOf(
            SessionExecutionOperation.Steer,
            SessionExecutionOperation.Configure,
            SessionExecutionOperation.Respond,
        )) {
            assertFalse(journal.permitsOperation(attempt.binding, operation))
        }
        assertTrue(journal.enterPrompt(attempt.binding, fixture.ref, work.request))
        assertFalse(journal.enterPrompt(attempt.binding, fixture.ref, work.request))
        assertFailsWith<IllegalStateException> { journal.begin(work, emptyList()) }
    }

    @Test
    fun `restart preserves isolation and denies replay without inventing a human owner`() = runTest {
        val attempt = journal.begin(work, listOf("shell"))
        journal.opened(attempt.binding, fixture.ref)
        journal.prepare(attempt.binding)
        journal.enterPrompt(attempt.binding, fixture.ref, work.request)
        val restarted = ServiceTurnJournal(store)
        assertFalse(restarted.permits(attempt.binding))
        assertFalse(restarted.enterPrompt(attempt.binding, fixture.ref, work.request))
        assertEquals(1, restarted.orphaned().size)
        val policy = AgentSafetyToolPolicy(restarted)
        assertEquals(ToolSwitch.Off, policy.policy(ToolPolicyScope(session = fixture.ref))?.native?.get("shell"))
        assertTrue(policy.isIsolated(fixture.ref))
    }

    @Test
    fun `Unknown cannot release isolation without explicit native stop proof`() = runTest {
        val attempt = journal.begin(work, listOf("shell"))
        journal.opened(attempt.binding, fixture.ref)
        journal.prepare(attempt.binding)
        journal.enterPrompt(attempt.binding, fixture.ref, work.request)
        val unknown = Turn(TurnId("native"), work.request, fixture.target, TurnOutcome.Unknown)
        assertFailsWith<IllegalStateException> { journal.settled(attempt.binding, unknown) }
        journal.retire(attempt.binding)
        journal.stopped(attempt.binding, OwnedTurnStop.Confirmed(unknown))
        assertNull(journal.isolation(fixture.ref))
        assertFalse(journal.permits(attempt.binding))
        assertFalse(store.raw.value.orEmpty().contains("private source"))
    }

    @Test
    fun `a lost entry write acknowledgement cannot authorize another send`() = runTest {
        val attempt = journal.begin(work, emptyList())
        journal.opened(attempt.binding, fixture.ref)
        journal.prepare(attempt.binding)
        store.afterWrite = { error("write ACK lost") }
        assertFailsWith<IllegalStateException> { journal.enterPrompt(attempt.binding, fixture.ref, work.request) }
        store.afterWrite = {}
        assertFalse(journal.enterPrompt(attempt.binding, fixture.ref, work.request))
        assertEquals(ServiceStage.Sending, ServiceTurnJournal(store).get(attempt.binding).stage)
    }

    @Test
    fun `corrupt and future records never become an empty authority`() = runTest {
        val attempt = journal.begin(work, emptyList())
        store.raw.value = "not json"
        assertFailsWith<IllegalArgumentException> { journal.permits(attempt.binding) }
        store.raw.value = """{"version":2,"attempts":[]}"""
        assertFailsWith<IllegalStateException> { journal.begin(work, emptyList()) }
        assertTrue(store.raw.value.orEmpty().contains("\"version\":2"))
    }
}
