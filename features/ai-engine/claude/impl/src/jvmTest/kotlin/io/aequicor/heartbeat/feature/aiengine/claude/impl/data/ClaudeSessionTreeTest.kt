package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionActivity
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ClaudeSessionTreeTest {
    private val root = SessionRef(ClaudeEngine.Id, ClaudeEngine.SessionSource, "root")
    private val turn = TurnId("turn")
    private fun ClaudeSessionTree.frame(text: String) = receive(Json.parseToJsonElement(text).jsonObject, turn)
    private fun spawn(call: String, parent: String? = null, background: Boolean = false): String =
        """{"type":"assistant","parent_tool_use_id":${parent?.let { "\"$it\"" } ?: "null"},
            "message":{"content":[{"type":"tool_use","id":"$call","name":"Agent",
            "input":{"description":"$call","prompt":"work $call","run_in_background":$background}}]}}"""

    @Test
    fun `native nested agents and task aliases are counted once and task updates terminate them`() = runTest {
        val tree = ClaudeSessionTree(root)
        tree.frame(spawn("a", background = true))
        tree.frame(spawn("a", background = true))
        tree.frame(spawn("b", "a", background = true))
        tree.frame("""{"type":"system","subtype":"task_started","tool_use_id":"a","task_id":"task-a"}""")
        assertEquals(2, tree.snapshot(SessionActivity.Running).activeCount)
        assertEquals(listOf(1, 2), tree.snapshot(SessionActivity.Running).descendants().map { it.depth })
        tree.frame("""{"type":"system","subtype":"task_updated","task_id":"task-a","patch":{"status":"completed"}}""")
        tree.frame("""{"type":"system","subtype":"task_updated","tool_use_id":"b","patch":{"status":"killed"}}""")
        val result = tree.snapshot(SessionActivity.Idle)
        assertEquals(0, result.activeCount)
        assertEquals(
            listOf(SessionActivity.Completed, SessionActivity.Cancelled),
            result.descendants().map {
                it.node.activity
            },
        )
        assertFalse(result.isActivityKnown)
        val restored = ClaudeSessionTree(root, tree.saved())
        assertEquals(result, restored.snapshot(SessionActivity.Idle))
    }

    @Test
    fun `task identity aliases deduplicate and lost observation clears confirmed activity`() = runTest {
        val tree = ClaudeSessionTree(root)
        tree.frame(spawn("a", background = true))
        tree.frame("""{"type":"system","subtype":"task_started","tool_use_id":"a","task_id":"shared"}""")
        tree.frame(spawn("b", background = true))
        tree.frame("""{"type":"system","subtype":"task_started","tool_use_id":"b","task_id":"shared"}""")
        assertEquals(1, tree.snapshot(SessionActivity.Running).activeCount)
        val child = tree.snapshot(SessionActivity.Running).descendants().single().node
        tree.observationEnded()
        assertEquals(SessionActivity.Unknown, tree.snapshot(SessionActivity.Idle).descendants().single().node.activity)
        val history = assertNotNull(tree.history(child.ref))
        tree.close()
        assertFailsWith<EngineException> { history.page() }
    }

    @Test
    fun `task lifecycle takes precedence over successful launch acknowledgement`() {
        val tree = ClaudeSessionTree(root)
        tree.frame(spawn("a").replace(""","run_in_background":false""", ""))
        tree.frame("""{"type":"system","subtype":"task_started","tool_use_id":"a","task_id":"task-a"}""")
        tree.frame(
            """{"type":"user","message":{"content":[
            {"type":"tool_result","tool_use_id":"a","content":"started"}]}}""",
        )
        assertEquals(1, tree.snapshot(SessionActivity.Running).activeCount)
        tree.frame("""{"type":"system","subtype":"task_notification","task_id":"task-a","status":"completed"}""")
        assertEquals(0, tree.snapshot(SessionActivity.Running).activeCount)
    }

    @Test
    fun `original child images survive reordered native fields and restart as resource references`() = runTest {
        val image = ContentPart.Image(ResourceRef("attachment:logo", "image/png"))
        val block = Json.parseToJsonElement(
            """{"type":"image","source":{"type":"base64",
            "media_type":"image/png","data":"AQ=="}}""",
        ).jsonObject
        val tree = ClaudeSessionTree(root, originalInputs = mapOf(claudeInputKey(block) to listOf(image)))
        tree.frame(spawn("a"))
        tree.frame(
            """{"type":"user","parent_tool_use_id":"a","message":{"content":[
            {"source":{"data":"AQ==","media_type":"image/png","type":"base64"},"type":"image"}]}}""",
        )
        val restored = ClaudeSessionTree(root, tree.saved(), tree.inputReferences())
        val ref = restored.snapshot(SessionActivity.Idle).descendants().single().node.ref
        val last = assertNotNull(restored.history(ref)).page().items.filterIsInstance<SessionItem.Message>().last()
        assertEquals(MessageRole.User, last.role)
        assertEquals(listOf(image), last.parts)
    }

    @Test
    fun `restoring preserves child history but never claims old running agents are active`() = runTest {
        val tree = ClaudeSessionTree(root)
        tree.frame(spawn("a"))
        tree.frame(
            """{"type":"assistant","parent_tool_use_id":"a",
            "message":{"content":[{"type":"text","text":"child-only"}]}}""",
        )
        tree.frame("""{"type":"system","subtype":"task_started","task_id":"bash-task","tool_use_id":"unknown"}""")
        val restored = ClaudeSessionTree(root, tree.saved())
        val child = restored.snapshot(SessionActivity.Unknown).descendants().single().node
        assertEquals(SessionActivity.Unknown, child.activity)
        val history = assertNotNull(restored.history(child.ref)).page().items.filterIsInstance<SessionItem.Message>()
        assertEquals(2, history.size)
        tree.cancelled()
        assertEquals(
            SessionActivity.Cancelled,
            tree.snapshot(SessionActivity.Idle).descendants().single().node.activity,
        )
    }
}
