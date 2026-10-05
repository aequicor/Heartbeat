package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionActivity
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTrees
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
}
