package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionActivity
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CodexSessionObservationTest {
    @Test
    fun `completed tree waits for events then resumes polling a new root turn`() = runTest {
        val fixture = ObservationFixture(Fixture(this))
        val snapshots = mutableListOf<SessionTreeSnapshot>()
        val observation = backgroundScope.launch { fixture.observe().collect { snapshots += it } }
        runCurrent()
        assertEquals(SessionActivity.Completed, snapshots.last().nodes.single().activity)
        val idleRequests = fixture.requestCount
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(idleRequests, fixture.requestCount)
        assertFalse(observation.isCompleted)

        fixture.states["root"] = "active"
        fixture.changed("turn/started")
        runCurrent()
        assertEquals(SessionActivity.Running, snapshots.last().nodes.single().activity)
        // activeCount excludes the root, but a running root must keep fallback polling alive.
        assertEquals(0, snapshots.last().activeCount)
        val activeRequests = fixture.requestCount
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(fixture.requestCount > activeRequests)

        // Completion must also be discovered when the terminal notification is missed.
        fixture.states["root"] = "idle"
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(SessionActivity.Completed, snapshots.last().nodes.single().activity)
        val completedRequests = fixture.requestCount
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(completedRequests, fixture.requestCount)
        observation.cancel()
    }

    @Test
    fun `completed root keeps polling until its active descendant completes`() = runTest {
        val fixture = ObservationFixture(Fixture(this))
        fixture.states["child"] = "active"
        val snapshots = mutableListOf<SessionTreeSnapshot>()
        val observation = backgroundScope.launch { fixture.observe().collect { snapshots += it } }
        runCurrent()
        assertEquals(1, snapshots.last().activeCount)
        val activeRequests = fixture.requestCount
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(fixture.requestCount > activeRequests)

        fixture.states["child"] = "idle"
        fixture.changed("turn/completed", "child")
        runCurrent()
        assertEquals(0, snapshots.last().activeCount)
        val completedRequests = fixture.requestCount
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(completedRequests, fixture.requestCount)
        observation.cancel()
    }

    @Test
    fun `unknown or incomplete tree retains fallback polling`() = runTest {
        for (isPartial in listOf(false, true)) {
            val fixture = ObservationFixture(Fixture(this))
            fixture.states["root"] = if (isPartial) "idle" else "notLoaded"
            fixture.isCanonical = !isPartial
            val observation = backgroundScope.launch { fixture.observe().collect {} }
            runCurrent()
            val requests = fixture.requestCount
            advanceTimeBy(2_000)
            runCurrent()
            assertTrue(fixture.requestCount > requests)
            observation.cancel()
            fixture.fixture.runtime.close()
        }
    }

    @Test
    fun `history waits when completed and still delivers changes after a new turn`() = runTest {
        val fixture = ObservationFixture(Fixture(this))
        val history = fixture.trees.history(fixture.root, fixture.root, fixture.access)
        val page = history.page()
        val events = mutableListOf<SessionEvent>()
        val observation = backgroundScope.launch { history.watch(page.checkpoint).collect { events += it } }
        runCurrent()
        val idleRequests = fixture.requestCount
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(idleRequests, fixture.requestCount)
        assertTrue(events.isEmpty())

        fixture.states["root"] = "active"
        fixture.reply = "updated"
        fixture.changed("turn/started")
        runCurrent()
        assertTrue(events.any { it is SessionEvent.ItemUpserted })
        val activeRequests = fixture.requestCount
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(fixture.requestCount > activeRequests)

        fixture.states["root"] = "idle"
        fixture.changed("turn/completed")
        runCurrent()
        val completedRequests = fixture.requestCount
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(completedRequests, fixture.requestCount)
        assertFalse(observation.isCompleted)
        observation.cancel()
    }

    @Test
    fun `invalidation during the first snapshot is retained and cancellation stops reads`() = runTest {
        val fixture = ObservationFixture(Fixture(this))
        val delegate = fixture.fixture.wire.handler
        var invalidated = false
        fixture.fixture.wire.handler = { message ->
            delegate(message)
            if (!invalidated && message.text("method") == "thread/turns/list") {
                invalidated = true
                fixture.states["root"] = "active"
                fixture.fixture.runtime.treeChanges.emit(Unit)
            }
        }
        val snapshots = mutableListOf<SessionTreeSnapshot>()
        val observation = backgroundScope.launch { fixture.observe().collect { snapshots += it } }
        runCurrent()
        assertEquals(SessionActivity.Running, snapshots.last().nodes.single().activity)
        observation.cancel()
        runCurrent()
        assertEquals(0, fixture.fixture.runtime.treeChanges.subscriptionCount.value)
        val requests = fixture.requestCount
        fixture.changed("turn/completed")
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(requests, fixture.requestCount)
    }
}

private class ObservationFixture(val fixture: Fixture) {
    val root = SessionRef(CodexEngine.Id, SessionSourceId("codex.local"), "root")
    val access = SessionTreeAccess(fixture.target)
    val trees = CodexSessionTrees(fixture.runtime, fixture.rpc)
    val states = linkedMapOf("root" to "idle")
    var isCanonical = true
    var reply = "initial"
    val requestCount get() = fixture.wire.written.size

    init {
        val delegate = fixture.wire.handler
        fixture.wire.handler = { message ->
            val id = message.obj("params").text("threadId").orEmpty()
            when (message.text("method")) {
                "thread/read" -> fixture.wire.reply(message, thread(id))
                "thread/turns/list" -> fixture.wire.reply(message, turns(id))
                else -> delegate(message)
            }
        }
    }

    fun observe() = trees.observe(root, access)

    suspend fun changed(method: String, id: String = "root") = fixture.wire.event(method, json("threadId" to id.json()))

    private fun thread(id: String): JsonObject = Json.parseToJsonElement(
        """{"thread":{"id":"$id","parentThreadId":${if (id == "root") "null" else "\"root\""},
            "historyMode":"paginated","status":{"type":"${states[id]}"}}}""",
    ).jsonObject

    private fun turns(id: String): JsonObject {
        val child = if (id == "root" && "child" in states) {
            """,{"id":"spawn","type":"subAgentActivity","kind":"started","agentThreadId":"child"}"""
        } else {
            ""
        }
        val status = when (states[id]) {
            "idle" -> "completed"
            "active" -> "inProgress"
            else -> "unknown"
        }
        return Json.parseToJsonElement(
            """{"data":[{"id":"turn","status":"$status","itemsView":"${if (isCanonical) "full" else "summary"}",
                "items":[{"id":"reply","type":"agentMessage","text":"$reply"}$child]}]}""",
        ).jsonObject
    }
}
