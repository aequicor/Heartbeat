package io.aequicor.heartbeat.ds.components

import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HbCodeIndentTest {
    @Test
    fun `a caret inserts one indent where it stands`() {
        assertEquals(HbCodeEdit("val     a", TextRange(8)), indentHbCode("val a", TextRange(4)))
    }

    @Test
    fun `a selection indents every touched line except a final line reached at column zero`() {
        val text = "a\n\nb\nc"
        val edit = indentHbCode(text, TextRange(0, 5))
        assertEquals("    a\n\n    b\nc", edit.text)
        assertEquals(TextRange(0, 13), edit.selection)
    }

    @Test
    fun `outdent removes up to one indent or one tab per touched line`() {
        val edit = outdentHbCode("      a\n\tb\nc", TextRange(2, 10))
        assertEquals("  a\nb\nc", edit?.text)
        assertEquals(TextRange(0, 5), edit?.selection)
        assertNull(outdentHbCode("a\nb", TextRange(0, 3)))
    }

    @Test
    fun `gutter counts the line after a trailing line feed`() {
        assertEquals(1, hbCodeLineCount(""))
        assertEquals(3, hbCodeLineCount("a\nb\n"))
    }

    @Test
    fun `change covers only the text between the common prefix and suffix`() {
        val old = "a\nb\nc"
        val new = indentHbCode(old, TextRange(2, 5)).text
        val change = hbCodeChange(old, new)
        assertEquals(new, old.replaceRange(change.start, change.oldEnd, change.replacement))
        assertEquals(HbCodeChange(2, 2, "    "), hbCodeChange("a\nb", "a\n    b"))
        assertEquals(HbCodeChange(2, 2, ""), hbCodeChange("aa", "aa"))
    }
}
