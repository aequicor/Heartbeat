package io.aequicor.heartbeat.ds.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regressions from the PR #6 review: Markdown containers, line endings, nesting, emphasis and diffs. */
class HbReviewRegressionTest {
    @Test
    fun `fenced code inside quotes and list items drops container prefixes but keeps relative indent`() {
        assertEquals("val x = 1\nshow(x)", parseHbMarkdown("> ```kotlin\n> val x = 1\n> show(x)\n> ```").code())
        val listFence = "1. Install:\n   ```bash\n   npm install\n   npm test\n   ```"
        assertEquals("npm install\nnpm test", parseHbMarkdown(listFence).code())
        assertEquals("a\n  b", parseHbMarkdown("  ```\n  a\n    b\n  ```").code())
        assertEquals("indented code\nline2", parseHbMarkdown("    indented code\n    line2").code())
    }

    @Test
    fun `CRLF Markdown keeps fences headings and paragraphs apart`() {
        val source = "Intro\r\n\r\n```kotlin\r\nval x = 1\r\n```\r\n\r\nAfter **bold**\r\n\r\n## Next\r\n"
        val blocks = parseHbMarkdown(source)
        assertEquals(
            listOf(
                HbMarkdownBlockKind.Paragraph,
                HbMarkdownBlockKind.Code,
                HbMarkdownBlockKind.Paragraph,
                HbMarkdownBlockKind.Heading,
            ),
            blocks.map { it.kind },
        )
        assertEquals("val x = 1", blocks[1].content.text)
        assertEquals("After bold", blocks[2].content.text)
    }

    @Test
    fun `deeply nested quotes lists and emphasis never overflow the stack`() {
        val inputs = listOf(
            // Sizes that overflowed a 1 MB stack before nesting was bounded; the library itself parses them.
            ">".repeat(3_714) + " a",
            "- ".repeat(2_976) + "a",
            "*a _b ".repeat(928) + "c" + "_ d*".repeat(928),
        )
        inputs.forEach { source ->
            val blocks = parseHbMarkdown(source)
            assertTrue(blocks.isNotEmpty())
        }
    }

    @Test
    fun `literal markers inside emphasis remain text`() {
        assertEquals("2*3 = 6", parseHbMarkdown("**2*3 = 6**").single().content.text)
        assertEquals("~/.bashrc", parseHbMarkdown("**~/.bashrc**").single().content.text)
        assertEquals("x ~ y", parseHbMarkdown("~~x ~ y~~").single().content.text)
        val text = parseHbMarkdown("A **bold** and *it* word").single().content
        assertEquals("A bold and it word", text.text)
    }

    @Test
    fun `long fences are marked as one continuous segmented panel`() {
        val code = (1..60).joinToString("\n") { "val v$it = $it" }
        val blocks = parseHbMarkdown("```kotlin\n$code\n```")
        assertTrue(blocks.size > 1)
        assertTrue(blocks.first().isFirstSegment && !blocks.first().isLastSegment)
        assertTrue(!blocks.last().isFirstSegment && blocks.last().isLastSegment)
        assertEquals(code, blocks.joinToString("") { it.content.text })
        assertEquals("line", "line\n".withoutTrailingLineBreak())
        assertEquals("line", "line\r\n".withoutTrailingLineBreak())
    }

    @Test
    fun `plain text chunks never split CRLF and follow the shared line bound`() {
        val text = "a".repeat(2047) + "\r\nnext"
        val chunks = transcriptChunks(HbChatMessage("m", "Agent", text))
        val bodies = chunks.map { (it.body as HbTranscriptBody.Text).text }
        assertEquals(text, bodies.joinToString(""))
        assertFalse(bodies.any { it.startsWith("\n") }, "A continuation must not start with the previous line break")
    }

    @Test
    fun `blank context lines and inexact counts do not merge the next file`() {
        val source = "--- a/one.kt\n+++ b/one.kt\n@@ -1,3 +1,3 @@\n fun a() {\n\n-  old()\n+  new()\n" +
            "--- a/two.kt\n+++ b/two.kt\n@@ -1 +1 @@\n-x\n+y\n"
        assertEquals(listOf("one.kt", "two.kt"), chunkHbDiff(source).map { it.filePath }.distinct())
        val miscounted = "--- a/one.kt\n+++ b/one.kt\n@@ -1,9 +1,9 @@\n-a\n+b\n" +
            "--- a/two.kt\n+++ b/two.kt\n@@ -1 +1 @@\n-x\n+y\n"
        assertEquals(listOf("one.kt", "two.kt"), chunkHbDiff(miscounted).map { it.filePath }.distinct())
    }

    private fun List<HbMarkdownBlock>.code(): String =
        filter { it.kind == HbMarkdownBlockKind.Code }.joinToString("") { it.content.text }
}
