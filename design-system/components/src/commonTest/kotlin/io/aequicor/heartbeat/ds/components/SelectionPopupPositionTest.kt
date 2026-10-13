package io.aequicor.heartbeat.ds.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class SelectionPopupPositionTest {
    @Test
    fun `phone sheet stays above the keyboard and navigation inset`() {
        val safe = IntRect(0, 32, 420, 560)
        val provider = SelectionPopupPosition(safe, true, null, 16, 8)
        val position = provider.calculatePosition(
            IntRect.Zero,
            IntSize(420, 900),
            LayoutDirection.Ltr,
            IntSize(420, 320),
        )
        assertEquals(IntOffset(0, 240), position)
    }

    @Test
    fun `an anchor near the top uses the safe window instead of collapsing the list`() {
        val safe = IntRect(20, 24, 1000, 740)
        val anchor = IntRect(5, 30, 300, 100)
        val provider = SelectionPopupPosition(safe, false, anchor, 16, 8)
        val position = provider.calculatePosition(anchor, IntSize(1024, 768), LayoutDirection.Ltr, IntSize(360, 320))
        assertEquals(IntOffset(20, 24), position)
    }
}
