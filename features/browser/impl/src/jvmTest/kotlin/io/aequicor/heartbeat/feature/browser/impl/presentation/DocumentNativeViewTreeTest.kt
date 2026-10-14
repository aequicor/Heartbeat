package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentNativeViewTreeTest {
    @Test
    fun `only exact document content views beneath the owned root receive clipping`() {
        val tree = Tree()
        assertEquals(2, clipDocumentViewTree(1, tree))
        assertEquals(listOf(3L, 5L), tree.clipped)
        // Another window's content view is reachable only from its own root.
        assertEquals(1, clipDocumentViewTree(9, tree))
        assertEquals(listOf(3L, 5L, 10L), tree.clipped)
    }

    @Test
    fun `a window without the pinned native class stays unchanged`() {
        val tree = Tree()
        assertEquals(0, clipDocumentViewTree(4, tree))
        assertEquals(emptyList(), tree.clipped)
    }

    @Test
    fun `failure to enable clipping propagates instead of claiming a match`() {
        val tree = object : DocumentNativeViewTree by Tree() {
            override fun enableClipping(view: Long) = error("Unsupported native view")
        }
        assertFailsWith<IllegalStateException> { clipDocumentViewTree(1, tree) }
    }

    private class Tree : DocumentNativeViewTree {
        val clipped = mutableListOf<Long>()
        override fun children(view: Long): List<Long> = when (view) {
            1L -> listOf(2, 3, 4)
            2L -> listOf(5)
            9L -> listOf(10)
            else -> emptyList()
        }

        override fun isDocumentContent(view: Long): Boolean = view in listOf(3L, 5L, 10L)
        override fun enableClipping(view: Long) {
            clipped += view
        }
    }
}
