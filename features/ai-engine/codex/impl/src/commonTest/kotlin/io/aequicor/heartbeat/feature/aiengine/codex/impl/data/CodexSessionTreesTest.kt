package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionActivity
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTrees
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CodexSessionTreesTest {
    private val root = SessionRef(CodexEngine.Id, SessionSourceId("codex.local"), "root")
    private fun thread(id: String, parent: String?, status: String = "idle") = Json.parseToJsonElement(
        """{"id":"$id","parentThreadId":${parent?.let { "\"$it\"" } ?: "null"},
        "name":"$id","historyMode":"paginated","status":{"type":"$status"},"turns":[]}""",
    ).jsonObject

    @Test
    fun `discovery reads omitted nested children and histories without resuming or sending`() = runTest {
        val fixture = Fixture(this)
        val delegate = fixture.wire.handler
        val threads = listOf(
            thread("root", null, "active"),
            thread("child", "root", "active"),
            thread("nested", "child"),
        )
        fixture.wire.handler = { message ->
            val params = message.obj("params")
            when (message.text("method")) {
                "thread/list" -> fixture.wire.reply(message, json("data" to JsonArray(threads.drop(1))))

                "thread/read" -> fixture.wire.reply(
                    message,
                    json(
                        "thread" to threads.single {
                            it.text("id") == params.text("threadId")
                        },
                    ),
                )

                "thread/turns/list" -> {
                    val child = when (params.text("threadId")) {
                        "root" -> "child"
                        "child" -> "nested"
                        else -> null
                    }
                    val discovery = child?.let {
                        """,{"id":"spawn","type":"subAgentActivity","kind":"started","agentThreadId":"$it"}"""
                    }.orEmpty()
                    fixture.wire.reply(
                        message,
                        Json.parseToJsonElement(
                            """{"data":[{"id":"turn","status":"completed","itemsView":"full","items":[
                            {"id":"reply","type":"agentMessage","text":"child history"}$discovery]}]}""",
                        ).jsonObject,
                    )
                }

                else -> delegate(message)
            }
        }
        val feature = fixture.runtime.features.resolve(SessionTrees)
        val trees = (feature as io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess.Available).feature
        val access = SessionTreeAccess(fixture.target)
        val snapshot = CodexSessionTrees(fixture.runtime, fixture.rpc).snapshot(root, access)
        assertEquals(listOf(1, 2), snapshot.descendants().map { it.depth })
        assertEquals(1, snapshot.activeCount)
        assertEquals(SessionActivity.Completed, snapshot.descendants().last().node.activity)
        val history = trees.history(root, root.copy(nativeId = "nested"), access).page()
        assertEquals(1, history.items.size)
        val methods = fixture.wire.written.mapNotNull { it.text("method") }
        assertTrue(methods.none { it in setOf("thread/start", "thread/resume", "turn/start", "turn/interrupt") })
        assertTrue(methods.none { it == "thread/list" })
    }

    @Test
    fun `open session family is read through its execution process`() = runTest {
        val fixture = Fixture(this)
        val delegate = fixture.wire.handler
        fixture.wire.handler = { message ->
            val id = (message["params"] as? JsonObject)?.text("threadId").orEmpty()
            when (message.text("method")) {
                "thread/read" -> fixture.wire.reply(message, json("thread" to thread(id, null, "active")))
                "thread/turns/list" -> fixture.wire.reply(message, json("data" to JsonArray(emptyList())))
                else -> delegate(message)
            }
        }
        val session = fixture.open()
        val snapshot = fixture.runtime.trees.snapshot(session.ref, SessionTreeAccess(fixture.target))
        assertEquals(SessionActivity.Running, snapshot.nodes.single().activity)
        val reads = fixture.wire.written.filter { it.text("method") in setOf("thread/read", "thread/turns/list") }
        assertTrue(reads.isNotEmpty())
        assertTrue(reads.all { fixture.wire.origin(it) === fixture.wire.peers.single() })
        fixture.runtime.close()
    }

    @Test
    fun `idle execution process is kept while a native subagent still runs`() = runTest {
        val fixture = Fixture(this)
        var childStatus = "active"
        val delegate = fixture.wire.handler
        fixture.wire.handler = { message ->
            val id = (message["params"] as? JsonObject)?.text("threadId").orEmpty()
            when (message.text("method")) {
                "thread/read" -> fixture.wire.reply(
                    message,
                    json("thread" to if (id == "child") thread(id, "thread", childStatus) else thread(id, null)),
                )

                "thread/turns/list" -> fixture.wire.reply(
                    message,
                    json("data" to JsonArray(if (id == "thread") listOf(spawn("child")) else emptyList())),
                )

                else -> delegate(message)
            }
        }
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        fixture.event("item/completed", "turnId" to "native-turn".json(), "item" to spawn("child").items().single())
        fixture.event("turn/completed", "turn" to json("id" to "native-turn".json(), "status" to "completed".json()))
        runCurrent()
        session.close()
        runCurrent()
        val execution = fixture.wire.peers.single()
        assertFalse(execution.isClosed)
        childStatus = "idle"
        advanceTimeBy(CHILD_RECHECK)
        runCurrent()
        assertTrue(execution.isClosed)
        fixture.runtime.close()
    }

    @Test
    fun `idle execution process kept for subagents retires after the retention limit`() = runTest {
        val fixture = Fixture(this)
        val delegate = fixture.wire.handler
        fixture.wire.handler = { message ->
            val id = (message["params"] as? JsonObject)?.text("threadId").orEmpty()
            when (message.text("method")) {
                "thread/read" -> fixture.wire.reply(
                    message,
                    json("thread" to if (id == "child") thread(id, "thread", "active") else thread(id, null)),
                )

                "thread/turns/list" -> fixture.wire.reply(
                    message,
                    json("data" to JsonArray(if (id == "thread") listOf(spawn("child")) else emptyList())),
                )

                else -> delegate(message)
            }
        }
        val session = fixture.open()
        session.feature(SendsPrompts).send(Prompt)
        fixture.event("item/completed", "turnId" to "native-turn".json(), "item" to spawn("child").items().single())
        fixture.event("turn/completed", "turn" to json("id" to "native-turn".json(), "status" to "completed".json()))
        runCurrent()
        session.close()
        runCurrent()
        val execution = fixture.wire.peers.single()
        val reads = { fixture.wire.written.count { it.text("method") == "thread/read" } }
        val before = reads()
        advanceTimeBy(HALF_HOUR)
        runCurrent()
        assertFalse(execution.isClosed)
        // Rechecks back off to the maximum interval instead of polling every few seconds.
        assertTrue(reads() - before < HALF_HOUR / CodexSessionChildren.RECHECK_MILLIS)
        advanceTimeBy(CodexSessionChildren.MAX_RETENTION_MILLIS)
        runCurrent()
        assertTrue(execution.isClosed)
        fixture.runtime.close()
    }

    @Test
    fun `release while a retention recheck is pending does not restart the retention period`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.keptForActiveChild()
        val execution = fixture.wire.peers.single()
        advanceTimeBy(CodexSessionChildren.MAX_RETENTION_MILLIS - TEN_MINUTES)
        runCurrent()
        assertFalse(execution.isClosed)
        // A lease attached and released in between is another release of the same idle period.
        fixture.runtime.attach(session.ref, ResumeSessionRequest(fixture.target)).close()
        runCurrent()
        advanceTimeBy(2 * TEN_MINUTES)
        runCurrent()
        assertTrue(execution.isClosed)
        fixture.runtime.close()
    }

    @Test
    fun `retention period ends while the session is in use`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.keptForActiveChild()
        val execution = fixture.wire.peers.single()
        advanceTimeBy(CodexSessionChildren.MAX_RETENTION_MILLIS - TEN_MINUTES)
        runCurrent()
        val lease = fixture.runtime.attach(session.ref, ResumeSessionRequest(fixture.target))
        // The pending recheck fires while the session is in use and ends the period.
        advanceTimeBy(2 * TEN_MINUTES)
        runCurrent()
        lease.close()
        runCurrent()
        advanceTimeBy(HALF_HOUR)
        runCurrent()
        assertFalse(execution.isClosed)
        fixture.runtime.close()
    }

    /** A closed session whose idle execution process is kept for an active native subagent. */
    private suspend fun Fixture.keptForActiveChild(): ActiveSession {
        val delegate = wire.handler
        wire.handler = { message ->
            val id = (message["params"] as? JsonObject)?.text("threadId").orEmpty()
            when (message.text("method")) {
                "thread/read" -> wire.reply(
                    message,
                    json("thread" to if (id == "child") thread(id, "thread", "active") else thread(id, null)),
                )

                "thread/turns/list" -> wire.reply(
                    message,
                    json("data" to JsonArray(if (id == "thread") listOf(spawn("child")) else emptyList())),
                )

                else -> delegate(message)
            }
        }
        val session = open()
        session.feature(SendsPrompts).send(Prompt)
        event("item/completed", "turnId" to "native-turn".json(), "item" to spawn("child").items().single())
        event("turn/completed", "turn" to json("id" to "native-turn".json(), "status" to "completed".json()))
        test.runCurrent()
        session.close()
        test.runCurrent()
        assertFalse(wire.peers.single().isClosed)
        return session
    }

    private fun spawn(child: String): JsonObject = Json.parseToJsonElement(
        """{"id":"native-turn","status":"completed","itemsView":"full","items":[
        {"id":"spawn","type":"subAgentActivity","kind":"started","agentThreadId":"$child"}]}""",
    ).jsonObject

    private fun JsonObject.items(): List<JsonObject> = array("items").map { it as JsonObject }

    @Test
    fun `a thread without its first message is an empty partial family and not a failure`() = runTest {
        val fixture = Fixture(this)
        val delegate = fixture.wire.handler
        fixture.wire.handler = { message ->
            when (message.text("method")) {
                "thread/read" -> fixture.wire.reply(message, json("thread" to thread("root", null)))

                "thread/turns/list" -> fixture.wire.error(
                    message,
                    "thread root is not materialized yet; thread/turns/list is unavailable before first user message",
                )

                else -> delegate(message)
            }
        }
        val snapshot = CodexSessionTrees(fixture.runtime, fixture.rpc).snapshot(root, SessionTreeAccess(fixture.target))
        assertEquals(listOf(root), snapshot.nodes.map { it.ref })
        assertEquals(SessionTreeCoverage.Partial, snapshot.coverage)
    }

    @Test
    fun `a thread that is just starting its first turn is an empty partial family too`() = runTest {
        val fixture = Fixture(this)
        val delegate = fixture.wire.handler
        fixture.wire.handler = { message ->
            when (message.text("method")) {
                "thread/read" -> fixture.wire.reply(message, json("thread" to thread("root", null)))
                "thread/turns/list" -> fixture.wire.error(message, "paginated_threads is not supported yet")
                else -> delegate(message)
            }
        }
        val snapshot = CodexSessionTrees(fixture.runtime, fixture.rpc).snapshot(root, SessionTreeAccess(fixture.target))
        assertEquals(SessionTreeCoverage.Partial, snapshot.coverage)
    }

    @Test
    fun `any other rejected history read still fails`() = runTest {
        val fixture = Fixture(this)
        val delegate = fixture.wire.handler
        fixture.wire.handler = { message ->
            when (message.text("method")) {
                "thread/read" -> fixture.wire.reply(message, json("thread" to thread("root", null)))
                "thread/turns/list" -> fixture.wire.error(message, "thread root is gone")
                else -> delegate(message)
            }
        }
        val trees = CodexSessionTrees(fixture.runtime, fixture.rpc)
        assertFailsWith<EngineException> { trees.snapshot(root, SessionTreeAccess(fixture.target)) }
    }

    @Test
    fun `noncanonical child replay stays empty and partial so saved transcripts survive`() = runTest {
        val full = """{"id":"turn","itemsView":"full","items":[
            {"id":"synthetic","type":"agentMessage","text":"lossy replay"}]}"""
        val cases = listOf(
            Triple("legacy", "[$full]", false),
            Triple("legacy", "[]", false),
            Triple("paginated", "[]", false),
            Triple("paginated", """[{"id":"turn","itemsView":"full"}]""", false),
            Triple("paginated", """[{"id":"turn","items":[]}]""", false),
            Triple("paginated", "[${full.replace("full", "summary")}]", false),
            Triple("paginated", "[$full]", true),
        )
        for ((mode, turns, pendingItems) in cases) {
            val fixture = Fixture(this)
            val delegate = fixture.wire.handler
            fixture.wire.handler = { message ->
                when (message.text("method")) {
                    "thread/read" -> fixture.wire.reply(
                        message,
                        Json.parseToJsonElement(
                            """{"thread":{"id":"root","historyMode":"$mode","turns":$turns}}""",
                        ).jsonObject,
                    )

                    "thread/turns/list" -> fixture.wire.reply(
                        message,
                        Json.parseToJsonElement(
                            """{"data":$turns,"itemsBackwardsCursor":${if (pendingItems) "\"older\"" else "null"}}""",
                        ).jsonObject,
                    )

                    else -> delegate(message)
                }
            }
            val trees = CodexSessionTrees(fixture.runtime, fixture.rpc)
            val page = trees.history(root, root, SessionTreeAccess(fixture.target)).page()
            assertEquals(HistoryCoverage.Partial, page.coverage, "$mode: $turns")
            assertTrue(page.items.isEmpty(), "$mode: $turns must not replace durable native items")
            fixture.runtime.close()
        }
    }

    @Test
    fun `canonical history follows all turn pages and retains its journal after lossy refresh`() = runTest {
        val fixture = Fixture(this)
        val delegate = fixture.wire.handler
        var isCanonical = true
        fixture.wire.handler = { message ->
            when (message.text("method")) {
                "thread/read" -> fixture.wire.reply(
                    message,
                    json("thread" to observedThread(isCanonical)),
                )

                "thread/turns/list" -> {
                    val isLast = message.obj("params").text("cursor") != null
                    val id = if (isLast) "last" else "first"
                    fixture.wire.reply(message, replayPage(id, if (isLast) null else "next"))
                }

                else -> delegate(message)
            }
        }
        val trees = CodexSessionTrees(fixture.runtime, fixture.rpc)
        val history = trees.history(root, root, SessionTreeAccess(fixture.target))
        val page = history.page()
        assertEquals(HistoryCoverage.Complete, page.coverage)
        assertEquals(listOf("first", "last"), page.items.map { it.info.id.value })
        isCanonical = false
        val lossy = history.page()
        assertEquals(HistoryCoverage.Partial, lossy.coverage)
        assertEquals(page.items, lossy.items)
        fixture.runtime.close()
    }

    private fun observedThread(isCanonical: Boolean): JsonObject = if (isCanonical) {
        thread("root", null)
    } else {
        json("id" to "root".json(), "turns" to JsonArray(emptyList()))
    }

    private fun replayPage(id: String, cursor: String?): JsonObject = Json.parseToJsonElement(
        """{"data":[{"id":"turn-$id","itemsView":"full","items":[
            {"id":"$id","type":"agentMessage","text":"$id"}]}],
            "nextCursor":${cursor?.let { "\"$it\"" } ?: "null"}}""",
    ).jsonObject

    @Test
    fun `structured parent fallback and awaiting status are parsed without preview heuristics`() {
        val native = Json.parseToJsonElement(
            """{"id":"child","preview":"parent is someone else","source":{"subagent":{"thread_spawn":{
                "parent_thread_id":"root"}}},"status":{"type":"active","activeFlags":["waitingOnUserInput"]}}""",
        ).jsonObject
        val node = native.treeNode(root)
        assertEquals(root, node.parent)
        assertEquals(SessionActivity.AwaitingUser, node.activity)
        assertEquals(SessionActivity.Unknown, thread("child", "root", "notLoaded").treeNode(root).activity)
    }

    private companion object {
        const val CHILD_RECHECK = 5_001L
        const val HALF_HOUR = 30 * 60 * 1_000L
        const val TEN_MINUTES = 10 * 60 * 1_000L
    }
}
