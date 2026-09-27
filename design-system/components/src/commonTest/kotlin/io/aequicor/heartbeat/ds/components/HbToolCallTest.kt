package io.aequicor.heartbeat.ds.components

import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HbToolCallTest {
    @Test
    fun `large mixed tool payloads have unique bounded rows and preserve literal output`() {
        val console = (1..300).joinToString("\n") { "INFO $it **literal console**" }
        val rows = prepareToolRows(
            persistentListOf(
                HbToolBlock.Markdown("md", "## Summary\n\n**Done**"),
                HbToolBlock.Console("console", console),
                HbToolBlock.Diff("diff", "--- a/file.kt\n+++ b/file.kt\n@@ -1 +1 @@\n-old\n+new"),
            ),
        )
        assertTrue(rows.size > 10)
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
        val consoleRows = rows.filter { it.section == null && it.markdown == null && !it.isDiff }
        assertEquals(console, consoleRows.joinToString("") { it.text })
        assertTrue(rows.all { it.text.length <= MARKDOWN_CHUNK_CHARACTERS })
        assertEquals("Done", rows.first { it.markdown?.kind == HbMarkdownBlockKind.Paragraph }.markdown?.content?.text)
    }

    @Test
    fun `block ids containing separators cannot collide with generated Markdown or literal row ids`() {
        val rows = prepareToolRows(
            persistentListOf(
                HbToolBlock.Markdown("a", "text"),
                HbToolBlock.Console("a:0", "console"),
                HbToolBlock.Diff("a:0:section", "+change"),
            ),
        )
        assertEquals(6, rows.size)
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
        assertEquals(
            listOf("text", "console", "+change"),
            rows.filter { it.section == null }.map { it.markdown?.content?.text ?: it.text },
        )
    }

    @Test
    fun `unified diff classifies changes while keeping file headers neutral`() {
        val source = "--- a/file.kt\n+++ b/file.kt\n@@ -1 +1 @@\n-removed\n+added"
        val row = prepareToolRows(persistentListOf(HbToolBlock.Diff("diff", source))).single { it.isDiff }
        val diff = assertNotNull(row.diff)
        assertEquals("file.kt", diff.filePath)
        assertEquals(
            listOf(HbTone.Neutral, HbTone.Neutral, HbTone.Brand, HbTone.Danger, HbTone.Success),
            diff.lineTones,
        )
    }

    @Test
    fun `duplicate tool and payload identities are rejected before reaching lazy layout`() {
        assertFailsWith<IllegalArgumentException> {
            HbToolCall(
                "tool",
                "Tool",
                blocks = persistentListOf(HbToolBlock.Console("a", "one"), HbToolBlock.Diff("a", "two")),
            )
        }
        val call = HbToolCall("tool", "Tool")
        assertFailsWith<IllegalArgumentException> {
            HbChatMessage("message", "Agent", "", toolCalls = persistentListOf(call, call))
        }
    }
}
