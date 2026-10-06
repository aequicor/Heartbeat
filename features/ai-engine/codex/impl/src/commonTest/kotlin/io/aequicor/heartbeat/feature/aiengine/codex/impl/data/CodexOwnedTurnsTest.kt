@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnStop
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.StopsOwnedTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexOwnedTurnsTest {
    @Test
    fun `native completion during an owned stop preserves its known outcome in the live lease`() = runTest {
        val (fixture, launch) = fixture()
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt)
        val exit = CompletableDeferred<Unit>()
        launch.onStop = {
            exit.await()
            true
        }
        val stopping = async { fixture.stop(session) }
        runCurrent()
        fixture.event(
            "turn/completed",
            "turn" to json("id" to "native-turn".json(), "status" to "completed".json()),
        )
        runCurrent()
        assertNotNull(fixture.environment.turns.get(session.ref)?.stopping)
        exit.complete(Unit)
        val result = assertIs<OwnedTurnStop.Confirmed>(stopping.await())
        assertEquals(turn, result.turn.id)
        assertEquals(TurnOutcome.Completed, result.turn.outcome)
        assertEquals(result.turn, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn)
        fixture.runtime.close()
    }

    @Test
    fun `invalid cold route does not poison a subsequent correctly routed stop`() = runTest {
        val records = MemoryCodexTurnRecords()
        val (first, _) = fixture(records)
        val session = first.open()
        session.feature(SendsPrompts).send(Prompt)
        first.runtime.close()
        val (second, launch) = fixture(records)
        assertFailsWith<EngineException> {
            second.runtime.ownedTurns.stop(
                session.ref,
                Prompt.id,
                OwnedTurnAccess(second.target, WorkspaceRef("wrong")),
            )
        }
        assertFalse(second.runtime.ownedTurns.isReserved(session.ref.nativeId))
        assertEquals(0, launch.stops)
        assertIs<OwnedTurnStop.Confirmed>(second.stop(session))
        assertEquals(1, launch.stops)
        second.runtime.close()
    }

    @Test
    fun `source binding and workspace mismatch cannot revoke or signal the active request`() = runTest {
        val (fixture, launch) = fixture()
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt)
        val written = fixture.wire.written.size
        assertFailsWith<EngineException> {
            fixture.runtime.ownedTurns.stop(
                session.ref.copy(source = SessionSourceId("foreign")),
                Prompt.id,
                OwnedTurnAccess(fixture.target),
            )
        }
        assertEquals(written, fixture.wire.written.size)
        for (access in listOf(
            OwnedTurnAccess(fixture.target.copy(binding = EngineBindingId("foreign"))),
            OwnedTurnAccess(fixture.target, WorkspaceRef("foreign")),
        )) {
            assertFailsWith<EngineException> { fixture.runtime.ownedTurns.stop(session.ref, Prompt.id, access) }
        }
        assertEquals(turn, assertIs<ActiveSessionState.Running>(session.state.value).turn.id)
        assertEquals(0, launch.stops)
        assertFalse(fixture.runtime.ownedTurns.isReserved(session.ref.nativeId))
        fixture.runtime.close()
    }

    @Test
    fun `cold stop refuses changed native account or home before any process signal`() = runTest {
        val records = MemoryCodexTurnRecords()
        val (first, _) = fixture(records)
        val session = first.open()
        session.feature(SendsPrompts).send(Prompt)
        first.runtime.close()
        for (changeHome in listOf(false, true)) {
            val (second, launch) = fixture(records)
            if (changeHome) {
                second.codexHome = "/other/home"
                second.rpc.initialize()
            } else {
                second.account = json("type" to "chatgpt".json(), "email" to "other@example.invalid".json())
            }
            assertFailsWith<EngineException> { second.stop(session) }
            assertEquals(0, launch.stops)
            assertTrue(second.wire.peers.isEmpty())
            second.runtime.close()
        }
    }

    @Test
    fun `failed exit receipt retains the live barrier and retry applies it before a new request`() = runTest {
        val records = GatedTurnRecords()
        val (fixture, _) = fixture(records)
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        records.beforeWrite = { if (it.last?.isProcessStopped == true) error("Disk unavailable") }
        assertFailsWith<EngineException> { fixture.stop(session) }
        assertNotNull(records.get(session.ref)?.stopping)
        assertNotNull(assertIs<ActiveSessionState.Unavailable>(session.state.value).activeTurn)
        assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("next"))) }
        records.beforeWrite = {}
        assertIs<OwnedTurnStop.Confirmed>(fixture.stop(session))
        assertNull(records.get(session.ref)?.stopping)
        assertIs<ActiveSessionState.Ready>(session.state.value)
        fixture.runtime.close()
    }

    @Test
    fun `reused request id selects the active turn in both live and cold runtimes`() = runTest {
        for (cold in listOf(false, true)) {
            val records = MemoryCodexTurnRecords()
            val (first, _) = fixture(records)
            val session = first.open()
            val old = session.feature(SendsPrompts).send(Prompt)
            first.event("turn/completed", "turn" to json("id" to "native-turn".json(), "status" to "completed".json()))
            runCurrent()
            val current = session.feature(SendsPrompts).send(Prompt)
            assertTrue(old != current)
            val runtime = if (cold) {
                first.runtime.close()
                fixture(records).first
            } else {
                first
            }
            val result = assertIs<OwnedTurnStop.Confirmed>(runtime.stop(session))
            assertEquals(current, result.turn.id)
            assertEquals(TurnOutcome.Unknown, result.turn.outcome)
            runtime.runtime.close()
        }
    }

    @Test
    fun `historical cancellation cannot close a different current request on the same process`() = runTest {
        val (fixture, launch) = fixture()
        val session = fixture.open()
        val old = session.feature(SendsPrompts).send(Prompt)
        fixture.event("turn/completed", "turn" to json("id" to "native-turn".json(), "status" to "completed".json()))
        runCurrent()
        val current = session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("next")))
        assertEquals(old, assertIs<OwnedTurnStop.Confirmed>(fixture.stop(session)).turn.id)
        assertEquals(current, assertIs<ActiveSessionState.Running>(session.state.value).turn.id)
        assertFalse(fixture.wire.peers.single().isClosed)
        assertEquals(0, launch.stops)
        fixture.runtime.close()
    }

    @Test
    fun `hosted cleanup remains a barrier during live stop reader failure and runtime replacement`() = runTest {
        for (scenario in listOf("live", "failed", "replaced")) {
            val records = MemoryCodexTurnRecords()
            val drains = CodexHostedDrains()
            val cleanup = CompletableDeferred<Unit>()
            val search = object : SearchEngine {
                override suspend fun search(query: String, count: Int, native: EngineFeatures?): List<SearchResult> {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { cleanup.await() }
                    }
                }
                override suspend fun fetch(url: String, native: EngineFeatures?): ResourceContent = error("Unused")
            }
            val launch = OwnedStopLaunch()
            val first = Fixture(this, search, turns = records, launch = launch, hostedDrains = drains)
            launch.wire = first.wire
            val session = first.open()
            session.feature(SendsPrompts).send(Prompt)
            first.event(
                "item/tool/call",
                "turnId" to "native-turn".json(),
                "tool" to "web_search".json(),
                "arguments" to json("query" to "topic".json()),
                id = JsonPrimitive(90),
            )
            runCurrent()
            if (scenario != "live") {
                first.wire.peers.single().incoming.close()
                runCurrent()
            }
            val secondLaunch = OwnedStopLaunch()
            val current = if (scenario == "replaced") {
                first.runtime.close()
                Fixture(this, turns = records, launch = secondLaunch, hostedDrains = drains).also {
                    secondLaunch.wire = it.wire
                }
            } else {
                first
            }
            assertEquals(OwnedTurnStop.Unconfirmed, current.stop(session))
            assertEquals(0, launch.stops + secondLaunch.stops)
            cleanup.complete(Unit)
            runCurrent()
            assertIs<OwnedTurnStop.Confirmed>(current.stop(session))
            current.runtime.close()
        }
    }

    @Test
    fun `confirmed native stop preserves the lease and next request opens a fresh execution`() = runTest {
        val (fixture, launch) = fixture()
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt)
        val result = assertIs<OwnedTurnStop.Confirmed>(fixture.stop(session))
        assertEquals(turn, result.turn.id)
        assertEquals(TurnOutcome.Unknown, result.turn.outcome)
        assertEquals(result.turn, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn)
        assertTrue(fixture.wire.peers.single().isClosed)
        assertEquals(1, launch.stops)
        val next = session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("next")))
        assertEquals(next, assertIs<ActiveSessionState.Running>(session.state.value).turn.id)
        assertEquals(2, fixture.wire.peers.size)
        fixture.runtime.close()
    }

    @Test
    fun `lost acknowledgement and reader failure cannot destroy a lease before the exit receipt`() = runTest {
        val (fixture, launch) = fixture()
        val session = fixture.open()
        fixture.onTurn = {}
        val sending = async { assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) } }
        runCurrent()
        val exit = CompletableDeferred<Unit>()
        launch.onStop = {
            fixture.wire.peers.single().incoming.close()
            exit.await()
            true
        }
        val stopping = async { fixture.stop(session) }
        runCurrent()
        sending.await()
        assertFalse(stopping.isCompleted)
        assertNotNull(assertIs<ActiveSessionState.Unavailable>(session.state.value).activeTurn)
        assertNotNull(fixture.environment.turns.get(session.ref)?.stopping)
        assertFailsWith<EngineException> {
            fixture.runtime.attach(session.ref, ResumeSessionRequest(fixture.target))
        }
        exit.complete(Unit)
        assertIs<OwnedTurnStop.Confirmed>(stopping.await())
        assertIs<ActiveSessionState.Ready>(session.state.value)
        assertNull(fixture.environment.turns.get(session.ref)?.stopping)
        fixture.runtime.close()
    }

    @Test
    fun `stop during durable begin proves not sent and never asks the OS to stop`() = runTest {
        val records = GatedTurnRecords()
        val (fixture, launch) = fixture(records)
        val session = fixture.open()
        records.beforeWrite = { if (it.active != null) records.gate.await() }
        val sending = async { assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) } }
        runCurrent()
        val stopping = async { fixture.stop(session) }
        runCurrent()
        sending.await()
        assertFalse(stopping.isCompleted)
        records.gate.complete(Unit)
        val result = assertIs<OwnedTurnStop.Confirmed>(stopping.await())
        assertEquals(TurnOutcome.Cancelled, result.turn.outcome)
        assertEquals(0, launch.stops)
        assertTrue(fixture.wire.written.none { it.text("method") == "turn/start" })
        assertIs<ActiveSessionState.Ready>(session.state.value)
        session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("next")))
        fixture.runtime.close()
    }

    @Test
    fun `stop during preflight settles a durable request without inventing an active machine turn`() = runTest {
        val (fixture, launch) = fixture()
        val session = fixture.open()
        val gate = CompletableDeferred<Unit>()
        val handler = fixture.wire.handler
        var isFirst = true
        fixture.wire.handler = {
            if (it.text("method") == "account/read" && isFirst) {
                isFirst = false
                gate.await()
            }
            handler(it)
        }
        val sending = async { assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) } }
        runCurrent()
        val stopping = async { fixture.stop(session) }
        runCurrent()
        assertFalse(stopping.isCompleted)
        gate.complete(Unit)
        sending.await()
        val result = assertIs<OwnedTurnStop.Confirmed>(stopping.await())
        assertEquals(TurnOutcome.Cancelled, result.turn.outcome)
        assertNull(assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn)
        assertEquals(result.turn, fixture.environment.turns.get(session.ref)?.last?.turn)
        assertEquals(0, launch.stops)
        assertTrue(fixture.wire.written.none { it.text("method") == "turn/start" })
        session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("next")))
        fixture.runtime.close()
    }

    @Test
    fun `cancelled stop wait retains admission and durable fence until the same request retries`() = runTest {
        val (fixture, launch) = fixture()
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        val exit = CompletableDeferred<Unit>()
        launch.onStop = {
            exit.await()
            true
        }
        val stopping = async { fixture.stop(session) }
        runCurrent()
        stopping.cancelAndJoin()
        assertNotNull(fixture.environment.turns.get(session.ref)?.stopping)
        assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("next"))) }
        exit.complete(Unit)
        assertIs<OwnedTurnStop.Confirmed>(fixture.stop(session))
        assertEquals(2, launch.stops)
        assertIs<ActiveSessionState.Ready>(session.state.value)
        fixture.runtime.close()
    }

    @Test
    fun `concurrent exact stops share one receipt and cannot clear each others reservation`() = runTest {
        val (fixture, launch) = fixture()
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        val exit = CompletableDeferred<Unit>()
        launch.onStop = {
            exit.await()
            true
        }
        val first = async { fixture.stop(session) }
        val second = async { fixture.stop(session) }
        runCurrent()
        exit.complete(Unit)
        assertEquals(assertIs<OwnedTurnStop.Confirmed>(first.await()), second.await())
        assertEquals(1, launch.stops)
        fixture.runtime.close()
    }

    @Test
    fun `cold stop never opens or resumes an execution and model selection does not filter the old turn`() = runTest {
        val records = MemoryCodexTurnRecords()
        val (first, _) = fixture(records)
        val original = first.open()
        original.feature(SendsPrompts).send(Prompt)
        first.runtime.close()
        val (second, launch) = fixture(records)
        val access = OwnedTurnAccess(second.target.copy(model = ModelId("other")))
        val result = assertIs<OwnedTurnStop.Confirmed>(second.runtime.ownedTurns.stop(original.ref, Prompt.id, access))
        assertEquals(first.target, result.turn.target)
        assertTrue(second.wire.peers.isEmpty())
        assertTrue(
            second.wire.written.none { it.text("method") in setOf("thread/start", "thread/resume", "turn/start") },
        )
        assertEquals(result, second.runtime.ownedTurns.stop(original.ref, Prompt.id, access))
        assertEquals(1, launch.stops)
        second.runtime.close()
    }

    @Test
    fun `wrong request or expected turn never stops or reserves the active request`() = runTest {
        val (fixture, launch) = fixture()
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt)
        assertEquals(
            OwnedTurnStop.Unconfirmed,
            fixture.runtime.ownedTurns.stop(session.ref, RequestId("other"), OwnedTurnAccess(fixture.target)),
        )
        assertEquals(
            OwnedTurnStop.Unconfirmed,
            fixture.runtime.ownedTurns.stop(
                session.ref,
                Prompt.id,
                OwnedTurnAccess(fixture.target, expectedTurn = TurnId("other")),
            ),
        )
        assertFalse(fixture.runtime.ownedTurns.isReserved(session.ref.nativeId))
        assertEquals(turn, assertIs<ActiveSessionState.Running>(session.state.value).turn.id)
        assertEquals(0, launch.stops)
        fixture.runtime.close()
    }

    private suspend fun Fixture.stop(session: ActiveSession): OwnedTurnStop {
        val feature = assertIs<FeatureAccess.Available<StopsOwnedTurns>>(
            runtime.features.resolve(StopsOwnedTurns),
        ).feature
        return feature.stop(session.ref, Prompt.id, OwnedTurnAccess(target))
    }

    private fun TestScope.fixture(
        records: CodexTurnRecords = MemoryCodexTurnRecords(),
    ): Pair<Fixture, OwnedStopLaunch> {
        val launch = OwnedStopLaunch()
        return Fixture(this, turns = records, launch = launch).also { launch.wire = it.wire } to launch
    }
}

internal class OwnedStopLaunch : PreparedCodexLaunch {
    var wire: FakeWire? = null
    var stops = 0
    var onStop: suspend () -> Boolean = { true }
    override suspend fun open(): CodexWire = checkNotNull(wire).fork().also {
        it.owner = CodexExecutionOwner("launch-${checkNotNull(wire).peers.size}", CodexProcessIdentity(1, START))
    }
    override suspend fun stop(
        owner: CodexExecutionOwner,
        beginInspection: suspend () -> Boolean,
        record: suspend (CodexExecutionOwner) -> CodexExecutionOwner?,
    ): Boolean {
        stops++
        return beginInspection() && record(owner) != null && onStop()
    }

    private companion object {
        const val START = "2026-10-06T00:00:00Z"
    }
}
