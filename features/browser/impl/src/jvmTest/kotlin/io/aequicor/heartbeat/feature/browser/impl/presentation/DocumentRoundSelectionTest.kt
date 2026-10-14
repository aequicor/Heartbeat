package io.aequicor.heartbeat.feature.browser.impl.presentation

import java.awt.Rectangle
import java.awt.geom.Rectangle2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentRoundSelectionTest {
    @Test
    fun `main window selects its visible document and ignores hidden sibling browser children`() {
        val current = group()
        val hiddenChild = Node(isDocumentGroup = true, children = listOf(Node(isHidden = true)))
        val hiddenGroup = group().copy(isHidden = true)
        val root = Node(children = listOf(hiddenChild, hiddenGroup, current))
        assertEquals(listOf(current), visible(root))
    }

    @Test
    fun `multiple visible groups remain ambiguous rather than picking the first one`() {
        val first = group()
        val second = group()
        assertEquals(listOf(first, second), visible(Node(children = listOf(first, second))))
    }

    @Test
    fun `hidden ancestors empty groups and ordinary native views cannot become a target`() {
        val hiddenAncestor = Node(isHidden = true, children = listOf(group()))
        val emptyGroup = Node(isDocumentGroup = true)
        val ordinary = Node(children = listOf(Node()))
        assertTrue(visible(Node(children = listOf(hiddenAncestor, emptyGroup, ordinary))).isEmpty())
    }

    @Test
    fun `a sole native candidate must match the current attachment rather than a pending background frame`() {
        val expected = Rectangle(40, 60, 800, 600)
        assertTrue(Rectangle2D.Double(40.5, 59.5, 800.5, 599.5).matchesDocumentFrame(expected))
        assertFalse(Rectangle2D.Double(140.0, 60.0, 800.0, 600.0).matchesDocumentFrame(expected))
        assertFalse(Rectangle2D.Double(40.0, 60.0, 200.0, 600.0).matchesDocumentFrame(expected))
        assertFalse(Rectangle2D.Double().matchesDocumentFrame(expected))
        assertFalse(Rectangle2D.Double(40.0, 60.0, 800.0, 600.0).matchesDocumentFrame(Rectangle()))
    }

    private fun group() = Node(isDocumentGroup = true, children = listOf(Node()))

    private fun visible(root: Node) = visibleNativeDocumentGroups(
        root,
        children = Node::children,
        isDocumentGroup = Node::isDocumentGroup,
        isHidden = Node::isHidden,
    )

    private data class Node(
        val isDocumentGroup: Boolean = false,
        val isHidden: Boolean = false,
        val children: List<Node> = emptyList(),
    )
}
