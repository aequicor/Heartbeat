package io.aequicor.heartbeat.ds.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HbDiffChunkTest {
    @Test
    fun `git file boundaries preserve all metadata and associate each file with its new path`() {
        val first = "diff --git a/one.kt b/one.kt\nindex 123..456 100644\n" +
            "--- a/one.kt\n+++ b/one.kt\n@@ -1 +1 @@\n-old\n+new\n"
        val second = "diff --git a/two.kt b/two.kt\n--- a/two.kt\n+++ b/two.kt\n@@ -1 +1 @@\n-a\n+b"
        val chunks = chunkHbDiff(first + second)
        assertEquals(listOf("one.kt", "two.kt"), chunks.map { it.filePath })
        assertEquals(listOf(first, second), chunks.map { it.text })
        assertTrue(chunks.all { it.isFirst && it.isLast })
    }

    @Test
    fun `adjacent unified files handle additions deletions and renames`() {
        val addition = "--- /dev/null\n+++ b/added.kt\n@@ -0,0 +1 @@\n+new\n"
        val deletion = "--- a/removed.kt\n+++ /dev/null\n@@ -1 +0,0 @@\n-old\n"
        val rename = "--- a/old name.kt\n+++ b/new name.kt\n@@ -1 +1 @@\n-before\n+after\n"
        val chunks = chunkHbDiff(addition + deletion + rename)
        assertEquals(listOf("added.kt", "removed.kt", "new name.kt"), chunks.map { it.filePath })
        assertEquals(addition + deletion + rename, chunks.joinToString("") { it.text })
    }

    @Test
    fun `paths retain spaces drop tab timestamps and decode quoted Git UTF8 names`() {
        val source = "--- a/old file.kt\t2026-01-01 00:00:00\n" +
            "+++ b/new file.kt\t2026-01-02 00:00:00\n"
        assertEquals("new file.kt", chunkHbDiff(source).single().filePath)
        val quoted = "--- \"a/old \\\"name\\\".kt\"\n+++ \"b/caf\\303\\251 \\\"name\\\".kt\"\tstamp\n"
        assertEquals("café \"name\".kt", chunkHbDiff(quoted).single().filePath)
        assertEquals(quoted, chunkHbDiff(quoted).single().text)
    }

    @Test
    fun `only actual leading Git side prefixes are stripped`() {
        listOf("data/file.kt", "alpha/file.kt", "b-file.kt", "folder/a/file.kt", "/absolute/file.kt").forEach { path ->
            assertEquals(path, chunkHbDiff("--- $path\n+++ $path\n").single().filePath)
        }
        listOf("a/file.kt", "b/file.kt").forEach { path ->
            assertEquals(path, chunkHbDiff("--- $path\n+++ $path\n").single().filePath)
        }
        assertEquals("src/file.kt", chunkHbDiff("--- a/src/file.kt\n+++ b/src/file.kt\n").single().filePath)
    }

    @Test
    fun `hunk lines resembling file headers do not split files or replace their paths`() {
        val source = "--- a/real.kt\n+++ b/real.kt\n@@ -1 +1 @@\n--- removed content\n+++ added content\n"
        val chunk = chunkHbDiff(source).single()
        assertEquals("real.kt", chunk.filePath)
        assertEquals(source, chunk.text)
        assertTrue(chunk.isFirst && chunk.isLast)
        assertEquals(
            listOf(HbTone.Neutral, HbTone.Neutral, HbTone.Brand, HbTone.Danger, HbTone.Success, HbTone.Neutral),
            chunk.lineTones,
        )
    }

    @Test
    fun `long added and removed lines retain their original tone across opposite marker continuations`() {
        listOf('+' to HbTone.Success, '-' to HbTone.Danger).forEach { (marker, tone) ->
            val opposite = if (marker == '+') '-' else '+'
            val source = marker + "x".repeat(MARKDOWN_CHUNK_CHARACTERS - 1) +
                opposite + "y".repeat(MARKDOWN_CHUNK_CHARACTERS) + "🙂"
            val chunks = chunkHbDiff(source)
            assertTrue(chunks.size >= 3)
            assertEquals(opposite, chunks[1].text.first())
            assertEquals(source, chunks.joinToString("") { it.text })
            assertTrue(chunks.all { it.lineTones == listOf(tone) })
        }
    }

    @Test
    fun `header-like code inside a hunk remains added or removed while completed metadata stays neutral`() {
        val source = "--- a/file.kt\r\n+++ b/file.kt\r\n@@ -1 +1 @@\r\n---counter\r\n+++counter"
        val chunk = chunkHbDiff(source).single()
        assertEquals(
            listOf(HbTone.Neutral, HbTone.Neutral, HbTone.Brand, HbTone.Danger, HbTone.Success),
            chunk.lineTones,
        )
        assertEquals(source, chunk.text)
    }

    @Test
    fun `plain incomplete and metadata-only diffs retain text without guessing file names`() {
        val cases = listOf(
            "-removed\n+added\n",
            "--- a/incomplete.kt\n",
            "--- a/incomplete.kt\n+++ ",
            "--- a/old.kt\n+++ \"b/unfinished",
            "diff --git a/old.kt b/new.kt\nrename from old.kt\nrename to new.kt\n",
            "--- /dev/null\n+++ /dev/null\n",
            "",
        )
        cases.forEach { source ->
            val chunks = chunkHbDiff(source)
            assertEquals(source, chunks.joinToString("") { it.text })
            assertTrue(chunks.isNotEmpty())
            chunks.forEach { assertNull(it.filePath) }
        }
    }

    @Test
    fun `bounded chunks preserve CRLF surrogate pairs and first last markers per file`() {
        val first = "--- a/long.kt\r\n+++ b/long.kt\r\n" + "+details🙂\r\n".repeat(100)
        val second = "--- a/other.kt\r\n+++ b/other.kt\r\n+" + "🙂".repeat(2000)
        val chunks = chunkHbDiff(first + second)
        assertEquals(first + second, chunks.joinToString("") { it.text })
        assertTrue(chunks.size > 3)
        chunks.groupBy { it.filePath }.forEach { (path, fileChunks) ->
            assertTrue(path == "long.kt" || path == "other.kt")
            assertEquals(1, fileChunks.count { it.isFirst })
            assertEquals(1, fileChunks.count { it.isLast })
            assertTrue(fileChunks.first().isFirst)
            assertTrue(fileChunks.last().isLast)
        }
        chunks.forEach { chunk ->
            assertTrue(chunk.text.length <= MARKDOWN_CHUNK_CHARACTERS)
            assertFalse(chunk.text.first().isLowSurrogate())
            assertFalse(chunk.text.last().isHighSurrogate())
            assertFalse(chunk.text.endsWith('\r'))
            assertEquals(chunk.displayText.lineSequence().count(), chunk.lineTones.size)
        }
    }

    @Test
    fun `continuation display removes one boundary newline but retains intentional blank lines`() {
        val source = "--- a/file.kt\r\n+++ b/file.kt\r\n" + " line\r\n".repeat(21) + "\r\n\r\n+final\r\n"
        val chunks = chunkHbDiff(source)
        assertTrue(chunks.size > 1)
        assertEquals(source, chunks.joinToString("") { it.text })
        assertEquals(source, chunks.joinToString("\r\n") { it.displayText })
        assertTrue(chunks.last().displayText.endsWith("\r\n"))
        chunks.forEach { assertEquals(it.displayText.lineSequence().count(), it.lineTones.size) }
    }
}
