package io.aequicor.heartbeat.ds.components

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HbComposerMenuTest {
    @Test
    fun `popup is above the editor rather than its bottom toolbar and preserves its shadow gutter`() {
        val composer = IntRect(20, 500, 700, 720)
        val toolbarTrigger = IntRect(36, 652, 84, 700)
        val gutter = 30
        val position = ComposerPopupPosition(composer, gutter = gutter, gap = 8).calculatePosition(
            toolbarTrigger,
            IntSize(800, 760),
            LayoutDirection.Ltr,
            IntSize(420, 360),
        )
        assertEquals(IntOffset(6, 162), position)
        assertEquals(composer.top - 8, position.y + 360 - gutter)
    }

    @Test
    fun `popup stays in narrow windows and respects right to left anchoring`() {
        val bounds = IntRect(220, 300, 268, 348)
        val position = ComposerPopupPosition(null, gutter = 30, gap = 8).calculatePosition(
            bounds,
            IntSize(300, 480),
            LayoutDirection.Rtl,
            IntSize(280, 260),
        )
        assertEquals(18, position.x)
        assertTrue(position.y >= 0)
        assertTrue(position.x + 280 <= 300)
    }

    @Test
    fun `keyboard navigation wraps around disabled commands and traps tab inside menu`() {
        val enabled = listOf(0, 2, 5)
        assertEquals(2, composerMenuFocusIndex(Key.DirectionDown, false, 0, enabled))
        assertEquals(5, composerMenuFocusIndex(Key.DirectionUp, false, 0, enabled))
        assertEquals(0, composerMenuFocusIndex(Key.Tab, false, 5, enabled))
        assertEquals(5, composerMenuFocusIndex(Key.Tab, true, 0, enabled))
        assertEquals(0, composerMenuFocusIndex(Key.MoveHome, false, 5, enabled))
        assertEquals(5, composerMenuFocusIndex(Key.MoveEnd, false, 0, enabled))
        assertEquals(-1, composerMenuFocusIndex(Key.Tab, false, -1, emptyList()))
        assertNull(composerMenuFocusIndex(Key.Enter, false, 0, enabled))
    }
}
