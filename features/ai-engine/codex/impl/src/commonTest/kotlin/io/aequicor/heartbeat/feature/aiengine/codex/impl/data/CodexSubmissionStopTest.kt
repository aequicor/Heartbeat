@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CodexSubmissionStopTest {
    @Test
    fun `stop during final policy confirmation cannot cross native admission`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var lookups = 0
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun nativeToolsForExecution(scope: ToolPolicyScope): ResolvedToolPolicy {
                if (++lookups == 2) gate.await()
                return ResolvedToolPolicy()
            }
        }
        val fixture = Fixture(this, tools = tools)
        val session = fixture.directSession()
        val sending = async { assertFailsWith<EngineException> { session.send(Prompt) } }
        runCurrent()
        assertEquals(2, lookups)
        val pending = assertNotNull(session.pendingSubmission)
        pending.stop()
        sending.await()
        assertFalse(pending.boundary.isCompleted)
        gate.complete(Unit)
        runCurrent()
        assertEquals(CodexSubmissionBoundary.NotSent, pending.boundary.await())
        assertTrue(fixture.wire.written.none { it.text("method") == "turn/start" })
        fixture.runtime.close()
    }

    @Test
    fun `stop during account preflight prevents delayed submit and yields durable cancellation`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.directSession()
        val gate = CompletableDeferred<Unit>()
        val handler = fixture.wire.handler
        fixture.wire.handler = { message ->
            if (message.text("method") == "account/read") gate.await()
            handler(message)
        }
        val sending = async { assertFailsWith<EngineException> { session.send(Prompt) } }
        runCurrent()
        val pending = assertNotNull(session.pendingSubmission)
        pending.stop()
        assertFalse(pending.boundary.isCompleted)
        assertFalse(session.isUnused)
        gate.complete(Unit)
        sending.await()
        assertEquals(CodexSubmissionBoundary.NotSent, pending.boundary.await())
        assertTrue(fixture.wire.written.none { it.text("method") == "turn/start" })
        assertIs<ActiveSessionState.Ready>(session.machine.state.value)
        val journal = fixture.journal(session)
        val receipt = journal.cancelBeforeSubmission(pending, TrustLevel.Ask)
        assertEquals(pending.turn.copy(outcome = TurnOutcome.Cancelled), receipt.turn)
        assertFalse(receipt.isProcessStopped)
        assertSame(pending, session.pendingSubmission)
        assertFailsWith<EngineException> { session.send(Prompt.copy(id = RequestId("next"))) }
        assertEquals(receipt, journal.cancelBeforeSubmission(pending, TrustLevel.Ask))
        fixture.runtime.close()
    }

    @Test
    fun `stop during durable begin releases caller but waits for pre-native boundary`() = runTest {
        val records = GatedTurnRecords()
        val fixture = Fixture(this, turns = records)
        val session = fixture.directSession()
        records.beforeWrite = { if (it.active != null) records.gate.await() }
        val sending = async { assertFailsWith<EngineException> { session.send(Prompt) } }
        runCurrent()
        val pending = assertNotNull(session.pendingSubmission)
        pending.stop()
        assertEquals(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, Prompt.id), sending.await().failure)
        assertFalse(pending.boundary.isCompleted)
        assertTrue(session.connectionMutex.tryLock())
        session.connectionMutex.unlock()
        records.gate.complete(Unit)
        runCurrent()
        assertEquals(CodexSubmissionBoundary.NotSent, pending.boundary.await())
        assertTrue(fixture.wire.written.none { it.text("method") == "turn/start" })
        assertNull(records.get(session.ref)?.active)
        val receipt = fixture.journal(session).cancelBeforeSubmission(pending, TrustLevel.Ask)
        assertIs<TurnOutcome.Failed>(receipt.turn.outcome)
        fixture.runtime.close()
    }

    @Test
    fun `lost acknowledgement does not hold the stop boundary and late acceptance cannot revive it`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.directSession()
        var request: JsonObject? = null
        fixture.onTurn = { request = it }
        val sending = async { assertFailsWith<EngineException> { session.send(Prompt) } }
        runCurrent()
        val pending = assertNotNull(session.pendingSubmission)
        val boundary = assertIs<CodexSubmissionBoundary.NativeMayStart>(pending.boundary.await())
        assertSame(session.connection, boundary.origin)
        pending.stop()
        assertTrue(session.hostedJobs.isClosed(pending.turn.id))
        sending.await()
        assertTrue(session.connectionMutex.tryLock())
        session.connectionMutex.unlock()
        assertFailsWith<EngineException> { fixture.journal(session).cancelBeforeSubmission(pending, TrustLevel.Ask) }
        fixture.wire.reply(assertNotNull(request), json("turn" to json("id" to "native-turn".json())))
        runCurrent()
        assertIs<ActiveSessionState.Submitting>(session.machine.state.value)
        assertSame(pending, session.pendingSubmission)
        assertEquals("native-turn", fixture.journal(session).restore()?.active?.nativeId)
        assertEquals(1, fixture.wire.written.count { it.text("method") == "turn/start" })
        fixture.runtime.close()
    }

    @Test
    fun `cancelling sender during machine publication does not abandon profile-owned preparation`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.directSession()
        val gate = CompletableDeferred<Unit>()
        fixture.launcher.beforeSend = { if (it is ActiveSessionIntent.Public.Submit) gate.await() }
        val sending = async { session.send(Prompt) }
        runCurrent()
        val pending = assertNotNull(session.pendingSubmission)
        sending.cancelAndJoin()
        assertFalse(pending.boundary.isCompleted)
        assertFailsWith<EngineException> { session.send(Prompt.copy(id = RequestId("other"))) }
        assertSame(pending, session.pendingSubmission)
        pending.stop()
        gate.complete(Unit)
        runCurrent()
        assertEquals(CodexSubmissionBoundary.NotSent, pending.boundary.await())
        assertTrue(fixture.wire.written.none { it.text("method") == "turn/start" })
        assertSame(pending, session.pendingSubmission)
        fixture.runtime.close()
    }

    @Test
    fun `caller cancellation during publication still submits exactly once without a stop`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.directSession()
        val gate = CompletableDeferred<Unit>()
        fixture.launcher.beforeSend = { if (it is ActiveSessionIntent.Public.Submit) gate.await() }
        val sending = async { session.send(Prompt) }
        runCurrent()
        val pending = assertNotNull(session.pendingSubmission)
        sending.cancelAndJoin()
        gate.complete(Unit)
        runCurrent()
        assertIs<CodexSubmissionBoundary.NativeMayStart>(pending.boundary.await())
        assertEquals(pending.turn.id, assertIs<ActiveSessionState.Running>(session.machine.state.value).turn.id)
        assertEquals(1, fixture.wire.written.count { it.text("method") == "turn/start" })
        fixture.runtime.close()
    }

    @Test
    fun `completion before acknowledgement permits next request and old cleanup retains its entry`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.directSession()
        val requests = mutableListOf<JsonObject>()
        fixture.onTurn = { requests += it }
        val first = async { session.send(Prompt) }
        runCurrent()
        session.event(
            json(
                "method" to "turn/completed".json(),
                "params" to json("turn" to json("id" to "native-turn".json(), "status" to "completed".json())),
            ),
        )
        first.await()
        val next = async { session.send(Prompt.copy(id = RequestId("next"))) }
        runCurrent()
        val pending = assertNotNull(session.pendingSubmission)
        assertEquals(RequestId("next"), pending.turn.request)
        fixture.wire.reply(requests.first(), json("turn" to json("id" to "native-turn".json())))
        runCurrent()
        assertSame(pending, session.pendingSubmission)
        fixture.wire.reply(requests.last(), json("turn" to json("id" to "next-turn".json())))
        assertEquals(pending.turn.id, next.await())
        fixture.runtime.close()
    }

    @Test
    fun `pre-send receipt cannot settle another active request and a failed write keeps admission revoked`() = runTest {
        val records = GatedTurnRecords()
        val fixture = Fixture(this, turns = records)
        val session = fixture.directSession()
        val journal = fixture.journal(session)
        val turn = Turn(TurnId("not-sent"), Prompt.id, fixture.target)
        val pending = CodexSubmission(turn)
        pending.stop()
        pending.settled()
        records.beforeWrite = { error("Disk unavailable") }
        assertFailsWith<EngineException> { journal.cancelBeforeSubmission(pending, TrustLevel.Ask) }
        assertTrue(pending.isStopRequested)
        records.beforeWrite = {}
        val other = turn.copy(id = TurnId("other"), request = RequestId("other"))
        journal.begin(other, TrustLevel.Ask)
        assertFailsWith<EngineException> { journal.cancelBeforeSubmission(pending, TrustLevel.Ask) }
        assertEquals(other, journal.restore()?.active?.turn)
        assertNull(journal.restore()?.last)
        fixture.runtime.close()
    }

    @Test
    fun `busy rejection does not replace the pending request or its stop identity`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.directSession()
        fixture.onTurn = {}
        val sending = async { session.send(Prompt) }
        runCurrent()
        val pending = assertNotNull(session.pendingSubmission)
        sending.cancelAndJoin()
        assertFailsWith<EngineException> { session.send(Prompt.copy(id = RequestId("other"))) }
        assertSame(pending, session.pendingSubmission)
        assertEquals(Prompt.id, pending.turn.request)
        fixture.runtime.close()
    }

    private suspend fun Fixture.directSession(): CodexSession {
        runtime.gate()
        return CodexSession(
            SessionRef(runtime.identity.engine, environment.config.historySource, "thread"),
            ExecutionRoute(runtime.identity.engine, target.binding, runtime.identity.source, runtime.identity.revision),
            target,
            runtime,
            CodexConnection(rpc, test.backgroundScope, {}, { _, _ -> }),
        )
    }

    private fun Fixture.journal(session: CodexSession): CodexTurnJournal =
        CodexTurnJournal(environment.turns, session.ref, session.route, runtime.turnOwnership)
}
