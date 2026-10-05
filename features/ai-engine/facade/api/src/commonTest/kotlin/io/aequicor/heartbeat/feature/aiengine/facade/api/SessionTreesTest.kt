package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionTreesTest {
    private val root = SessionRef(EngineId("test"), SessionSourceId("local"), "root")
    private fun node(id: String, parent: String?, activity: SessionActivity) =
        SessionTreeNode(root.copy(nativeId = id), parent?.let { root.copy(nativeId = it) }, id, activity)

    @Test
    fun `nested descendants are ordered deduplicated and scoped to the stable root`() {
        val child = node("child", "root", SessionActivity.Running)
        val tree = SessionTreeSnapshot(
            root,
            listOf(
                node("root", "grandchild", SessionActivity.Running),
                child,
                child,
                node("grandchild", "child", SessionActivity.AwaitingUser),
                node("queued", "root", SessionActivity.Queued),
                node("idle", "root", SessionActivity.Idle),
                node("foreign", "other", SessionActivity.Running),
                child.copy(ref = child.ref.copy(source = SessionSourceId("other"))),
            ),
            SessionTreeCoverage.Complete,
        )
        assertEquals(listOf("child", "grandchild", "queued", "idle"), tree.descendants().map { it.node.name })
        assertEquals(listOf(1, 2, 1, 1), tree.descendants().map { it.depth })
        assertEquals(3, tree.activeCount)
        assertTrue(tree.isActivityKnown)
    }

    @Test
    fun `terminal and idle nodes are excluded while unknown prevents a confirmed zero`() {
        val tree = SessionTreeSnapshot(
            root,
            listOf(
                node("completed", "root", SessionActivity.Completed),
                node("cancelled", "root", SessionActivity.Cancelled),
                node("failed", "root", SessionActivity.Failed),
                node("idle", "root", SessionActivity.Idle),
            ),
            SessionTreeCoverage.Complete,
        )
        assertEquals(0, tree.activeCount)
        assertTrue(tree.isActivityKnown)
        assertFalse(tree.copy(nodes = tree.nodes + node("unknown", "root", SessionActivity.Unknown)).isActivityKnown)
        for (coverage in SessionTreeCoverage.entries.filter { it != SessionTreeCoverage.Complete }) {
            assertFalse(tree.copy(coverage = coverage).isActivityKnown)
        }
    }
}
