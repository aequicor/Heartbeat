package io.aequicor.heartbeat.ds.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HbLinksTest {
    @Test
    fun `external schemes are classified and bare hosts gain https`() {
        assertEquals(HbLinkTarget.Web("https://example.test/a"), parseHbLink(" <https://example.test/a> "))
        assertEquals(HbLinkTarget.Web("HTTP://example.test"), parseHbLink("HTTP://example.test"))
        assertEquals(HbLinkTarget.Web("https://www.example.test"), parseHbLink("www.example.test"))
        assertEquals(HbLinkTarget.Mail("mailto:dev@example.test"), parseHbLink("mailto:dev@example.test"))
        assertEquals(HbLinkTarget.Phone("tel:+15550100"), parseHbLink("tel:+15550100"))
        assertEquals(HbLinkTarget.Anchor("#usage", "usage"), parseHbLink("#usage"))
    }

    @Test
    fun `unsafe empty and oversized destinations have no target`() {
        listOf(
            "javascript:alert(1)",
            "data:text/html,hi",
            "vbscript:x",
            "",
            "#",
            "https://",
            "mailto:",
            "//example.test/x",
            "file://remote.example/etc/hosts",
            "a\u0000b",
            "a".repeat(5000),
        ).forEach { assertNull(parseHbLink(it), it) }
    }

    @Test
    fun `relative and absolute paths are files with an optional line`() {
        assertEquals(HbLinkTarget.File("src/Main.kt", "src/Main.kt"), parseHbLink("src/Main.kt"))
        assertEquals(HbLinkTarget.File("src/Main.kt:42", "src/Main.kt", 42), parseHbLink("src/Main.kt:42"))
        assertEquals(HbLinkTarget.File("Main.kt:42:7", "Main.kt", 42), parseHbLink("Main.kt:42:7"))
        assertEquals(HbLinkTarget.File("Makefile:9", "Makefile", 9), parseHbLink("Makefile:9"))
        assertEquals(HbLinkTarget.File("plan.md#L3-L9", "plan.md", 3), parseHbLink("plan.md#L3-L9"))
        assertEquals(HbLinkTarget.File("/Users/me/plan.md", "/Users/me/plan.md"), parseHbLink("/Users/me/plan.md"))
        assertEquals(HbLinkTarget.File("~/notes.md", "~/notes.md"), parseHbLink("~/notes.md"))
        assertEquals(HbLinkTarget.File("C:\\work\\a.kt:5", "C:\\work\\a.kt", 5), parseHbLink("C:\\work\\a.kt:5"))
        assertEquals("my plan.md", (parseHbLink("my%20plan.md") as HbLinkTarget.File).path)
        assertEquals("План.md", (parseHbLink("%D0%9F%D0%BB%D0%B0%D0%BD.md") as HbLinkTarget.File).path)
        assertEquals("100%.md", (parseHbLink("100%.md") as HbLinkTarget.File).path)
    }

    @Test
    fun `file uris keep local paths and windows drives`() {
        assertEquals("/project/a b.kt", (parseHbLink("file:///project/a%20b.kt") as HbLinkTarget.File).path)
        assertEquals("/project/a.kt", (parseHbLink("file://localhost/project/a.kt") as HbLinkTarget.File).path)
        assertEquals("C:/work/a.kt", (parseHbLink("file:///C:/work/a.kt") as HbLinkTarget.File).path)
        assertEquals(12, (parseHbLink("file:///project/a.kt#L12") as HbLinkTarget.File).line)
    }

    @Test
    fun `autolinks without a scheme become web or mail links`() {
        assertEquals("https://www.example.test", autolinkDestination("www.example.test"))
        assertEquals("mailto:dev@example.test", autolinkDestination("dev@example.test"))
        assertEquals("https://example.test", autolinkDestination("https://example.test"))
    }

    @Test
    fun `markdown keeps file links and autolinked hosts interactive`() {
        val block = parseHbMarkdown(
            "[plan](planning/plan.md:3) [site](www.example.test) www.example.test",
        ).single()
        val links = block.content.spans.filter { it.style == HbMarkdownStyle.Link }.map { it.destination }
        assertEquals(
            listOf(
                "planning/plan.md:3",
                "https://www.example.test",
                "https://www.example.test",
            ),
            links,
        )
    }
}
