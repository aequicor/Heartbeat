package io.aequicor.heartbeat.ds.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HbCodeSyntaxTest {
    @Test
    fun `Kotlin detects annotations keywords types and calls including Compose functions`() {
        val source = "@Composable fun Demo(count: Int = 42) { HbTheme { StudioConversation(); loadData(count) } }"
        assertEquals(
            listOf(
                "@Composable" to HbCodeTokenKind.Annotation,
                "fun" to HbCodeTokenKind.Keyword,
                "Demo" to HbCodeTokenKind.Function,
                "Int" to HbCodeTokenKind.Type,
                "42" to HbCodeTokenKind.Number,
                "HbTheme" to HbCodeTokenKind.Type,
                "StudioConversation" to HbCodeTokenKind.Function,
                "loadData" to HbCodeTokenKind.Function,
            ),
            codeTokens(source, "kotlin"),
        )
    }

    @Test
    fun `all language aliases use the same profile and ignore additional fence metadata`() {
        val source = "class Result { run(42); \"text\"; true; # note\n }"
        val aliases = listOf(
            listOf("kotlin", "kt", "kts"),
            listOf("java"),
            listOf("javascript", "js", "jsx", "typescript", "ts", "tsx"),
            listOf("json"),
            listOf("python", "py"),
            listOf("shell", "sh", "bash"),
        )
        aliases.forEach { group ->
            val expected = highlightHbCode(source, group.first())
            assertTrue(expected.isNotEmpty())
            group.forEach { alias ->
                assertEquals(expected, highlightHbCode(source, "  ${alias.uppercase()}\ttitle=Example  "))
            }
        }
    }

    @Test
    fun `unknown and plain language hints never guess from the source`() {
        val source = "fun Demo() = \"value\" // clearly Kotlin"
        listOf(null, "", " ", "text", "plain", "plaintext", "none", "ruby", "not-kotlin kotlin").forEach { hint ->
            assertTrue(highlightHbCode(source, hint).isEmpty(), "Unexpected highlighting for ${hint.orEmpty()}")
        }
    }

    @Test
    fun `escaped quotes and comment delimiters stay inside strings`() {
        val source = """
            val url = "https://host/\"quote\"/* keep */" // real "quote"
            val character = '\''
        """.trimIndent()
        assertEquals(
            listOf("\"https://host/\\\"quote\\\"/* keep */\"", "'\\''"),
            codeTokensOfKind(source, "kt", HbCodeTokenKind.String),
        )
        assertEquals(listOf("// real \"quote\""), codeTokensOfKind(source, "kt", HbCodeTokenKind.Comment))
    }

    @Test
    fun `nested Kotlin comments remain one span while Java ends at the first closing delimiter`() {
        val source = "/* outer /* inner */ tail */ return result"
        assertEquals(
            listOf("/* outer /* inner */ tail */"),
            codeTokensOfKind(source, "kotlin", HbCodeTokenKind.Comment),
        )
        assertEquals(listOf("/* outer /* inner */"), codeTokensOfKind(source, "java", HbCodeTokenKind.Comment))
        assertEquals(listOf("return"), codeTokensOfKind(source, "kotlin", HbCodeTokenKind.Keyword))
    }

    @Test
    fun `Kotlin raw triple quotes and Python docstrings protect multiline contents`() {
        val quote = "\"\"\""
        val raw = "$quote\n// not a comment\nval Fake = \"text\"\n$quote"
        val kotlin = "val text = $raw\nrender()"
        assertEquals(listOf(raw), codeTokensOfKind(kotlin, "kt", HbCodeTokenKind.String))
        assertEquals(listOf("val"), codeTokensOfKind(kotlin, "kt", HbCodeTokenKind.Keyword))
        assertTrue(codeTokensOfKind(kotlin, "kt", HbCodeTokenKind.Comment).isEmpty())
        val python = "'''a docstring\n# not a comment\nclass Fake'''\ndef run(): pass"
        assertEquals(
            listOf("'''a docstring\n# not a comment\nclass Fake'''"),
            codeTokensOfKind(python, "py", HbCodeTokenKind.String),
        )
        assertEquals(listOf("def", "pass"), codeTokensOfKind(python, "py", HbCodeTokenKind.Keyword))
    }

    @Test
    fun `unfinished strings escapes and block comments end at the available streaming boundary`() {
        val fragments = listOf(
            "val text = \"unfinished \\" to "kotlin",
            "val text = \"\"\"unfinished" to "kotlin",
            "const text = `unfinished ${'$'}{value}" to "ts",
            "'''unfinished docstring" to "py",
            "echo 'unfinished" to "bash",
            "/* outer /* inner */ unfinished" to "kt",
        )
        fragments.forEach { (source, language) ->
            val spans = highlightHbCode(source, language)
            assertEquals(source.length, spans.last().end)
            assertTrue(spans.last().kind in setOf(HbCodeTokenKind.String, HbCodeTokenKind.Comment))
            assertCodeRanges(source, spans)
        }
    }

    @Test
    fun `numbers include radix separators exponents and suffixes without consuming range operators`() {
        val source = "val a = 0xCAFEuL + 0b1010 + 12_345 + 6.02e+23 + 3.5f + 1..2"
        assertEquals(
            listOf("0xCAFEuL", "0b1010", "12_345", "6.02e+23", "3.5f", "1", "2"),
            codeTokensOfKind(source, "kt", HbCodeTokenKind.Number),
        )
        assertEquals(listOf(".5", "123n"), codeTokensOfKind("const value = .5 + 123n", "js", HbCodeTokenKind.Number))
    }

    @Test
    fun `JSON strings protect keys and string values while booleans and numeric literals are highlighted`() {
        val source = "{\"true\": true, \"value\": -1.25e-3, \"note\": \"// none\", \"empty\": null}"
        assertEquals(listOf("true", "null"), codeTokensOfKind(source, "json", HbCodeTokenKind.Keyword))
        assertEquals(listOf("1.25e-3"), codeTokensOfKind(source, "json", HbCodeTokenKind.Number))
        assertTrue(codeTokensOfKind(source, "json", HbCodeTokenKind.Comment).isEmpty())
        assertTrue(codeTokensOfKind("InvalidType call()", "json", HbCodeTokenKind.Type).isEmpty())
    }

    @Test
    fun `JavaScript template strings and shell quotes do not expose nested comment markers`() {
        val javascript = "const value = `hello ${'$'}{name} // text`; // real"
        assertEquals(
            listOf("`hello ${'$'}{name} // text`"),
            codeTokensOfKind(javascript, "tsx", HbCodeTokenKind.String),
        )
        assertEquals(listOf("// real"), codeTokensOfKind(javascript, "tsx", HbCodeTokenKind.Comment))
        val shell = "echo 'path\\' hi#fragment # real"
        assertEquals(listOf("'path\\'"), codeTokensOfKind(shell, "sh", HbCodeTokenKind.String))
        assertEquals(listOf("# real"), codeTokensOfKind(shell, "sh", HbCodeTokenKind.Comment))
    }

    @Test
    fun `backtick identifiers protect Kotlin keywords and Unicode offsets stay ordered in every prefix`() {
        val source = "val cafe\u0301 = \"🐱\"; `when`(); Пример; функция(42) /* 👩‍💻 nested */"
        assertEquals(listOf("val"), codeTokensOfKind(source, "kts", HbCodeTokenKind.Keyword))
        assertEquals(listOf("`when`", "функция"), codeTokensOfKind(source, "kts", HbCodeTokenKind.Function))
        val emojiString = highlightHbCode(source, "kt").first { it.kind == HbCodeTokenKind.String }
        assertEquals(source.indexOf('"'), emojiString.start)
        assertEquals(4, emojiString.end - emojiString.start)
        for (length in 0..source.length) {
            val prefix = source.take(length)
            assertCodeRanges(prefix, highlightHbCode(prefix, "kt"))
        }
    }

    @Test
    fun `deep unfinished block comments use iterative scanning`() {
        val source = "/*".repeat(4096) + "body" + "*/".repeat(4095)
        assertEquals(listOf(HbCodeSpan(0, source.length, HbCodeTokenKind.Comment)), highlightHbCode(source, "kt"))
    }
}

private fun codeTokens(source: String, language: String): List<Pair<String, HbCodeTokenKind>> =
    highlightHbCode(source, language).map { source.substring(it.start, it.end) to it.kind }

private fun codeTokensOfKind(source: String, language: String, kind: HbCodeTokenKind): List<String> =
    codeTokens(source, language).filter { it.second == kind }.map { it.first }

private fun assertCodeRanges(source: String, spans: List<HbCodeSpan>) {
    var previousEnd = 0
    spans.forEach { span ->
        assertTrue(span.start >= previousEnd && span.end > span.start && span.end <= source.length)
        assertTrue(span.start == 0 || !source[span.start].isLowSurrogate() || !source[span.start - 1].isHighSurrogate())
        assertTrue(
            span.end == source.length || !source[span.end].isLowSurrogate() || !source[span.end - 1].isHighSurrogate(),
        )
        previousEnd = span.end
    }
}
