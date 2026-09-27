package io.aequicor.heartbeat.ds.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class HbMenuPositionTest {
    private val position = HbMenuPosition(gap = 4)
    private val window = IntSize(800, 600)
    private val menu = IntSize(200, 150)

    @Test
    fun `menu opens below the anchor at its start edge`() {
        val anchor = IntRect(100, 100, 132, 132)
        assertEquals(IntOffset(100, 136), position.calculatePosition(anchor, window, LayoutDirection.Ltr, menu))
    }

    @Test
    fun `menu flips above an anchor near the bottom of the window`() {
        val anchor = IntRect(100, 540, 132, 572)
        assertEquals(IntOffset(100, 386), position.calculatePosition(anchor, window, LayoutDirection.Ltr, menu))
    }

    @Test
    fun `right to left menus align to the anchor end and stay inside the window`() {
        val anchor = IntRect(20, 100, 52, 132)
        assertEquals(IntOffset(0, 136), position.calculatePosition(anchor, window, LayoutDirection.Rtl, menu))
    }
}
