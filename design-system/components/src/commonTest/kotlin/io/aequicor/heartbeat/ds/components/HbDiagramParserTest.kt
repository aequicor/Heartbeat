package io.aequicor.heartbeat.ds.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HbDiagramParserTest {
    private val source = buildString {
        appendLine("@startuml")
        (1..40).forEach { appendLine("A$it -> B$it : step $it") }
        append("@enduml")
    }

    @Test
    fun `a closed plantuml fence becomes one unsegmented diagram row with the whole source`() {
        val blocks = parseHbMarkdown("Intro.\n\n```plantuml\n$source\n```\n\nOutro.")
        val diagram = blocks.single { it.kind == HbMarkdownBlockKind.Diagram }
        assertEquals(HbDiagramLanguage.PlantUml, diagram.diagram)
        assertEquals(source, diagram.content.text)
        assertEquals("plantuml", diagram.language)
        assertTrue(diagram.isFirstSegment && diagram.isLastSegment)
        assertTrue(diagram.id.endsWith(":diagram"))
        assertTrue(blocks.none { it.kind == HbMarkdownBlockKind.Code })
    }

    @Test
    fun `fence names are case-insensitive and puml is an alias`() {
        listOf("PlantUML", "puml", "plantuml title=flow").forEach { name ->
            val block = parseHbMarkdown("```$name\nA -> B\n```").single()
            assertEquals(HbMarkdownBlockKind.Diagram, block.kind, name)
            assertEquals(HbDiagramLanguage.PlantUml, block.diagram)
        }
    }

    @Test
    fun `an unfinished streamed diagram fence stays code until it is closed`() {
        val streaming = parseHbMarkdown("```plantuml\n$source")
        assertTrue(streaming.all { it.kind == HbMarkdownBlockKind.Code && it.diagram == null })
        assertEquals(source, streaming.joinToString("") { it.content.text })
        val closed = parseHbMarkdown("```plantuml\n$source\n```").single()
        assertEquals(HbMarkdownBlockKind.Diagram, closed.kind)
    }

    @Test
    fun `untagged fences are diagrams only for known start directives`() {
        val diagram = parseHbMarkdown("```\n\n@startmindmap\n* Root\n@endmindmap\n```").single()
        assertEquals(HbDiagramLanguage.PlantUml, diagram.diagram)
        assertNull(diagram.language)
        listOf("@startup routine", "@startdot\ndigraph {}\n@enddot", "A -> B").forEach { code ->
            val block = parseHbMarkdown("```\n$code\n```").first()
            assertEquals(HbMarkdownBlockKind.Code, block.kind, code)
        }
        assertEquals(HbMarkdownBlockKind.Code, parseHbMarkdown("```kotlin\n@startuml\n```").single().kind)
        assertEquals(HbMarkdownBlockKind.Code, parseHbMarkdown("    @startuml\n    A -> B").single().kind)
    }

    @Test
    fun `oversized diagram fences stay bounded code segments`() {
        val longSource = (0..MAX_DIAGRAM_LINES).joinToString("\n") { "A -> B : $it" }
        val lines = parseHbMarkdown("```plantuml\n$longSource\n```")
        assertTrue(lines.size > 1)
        assertTrue(lines.all { it.kind == HbMarkdownBlockKind.Code })
        val wide = "A -> B : " + "x".repeat(MAX_DIAGRAM_CHARACTERS)
        assertTrue(parseHbMarkdown("```plantuml\n$wide\n```").all { it.kind == HbMarkdownBlockKind.Code })
    }

    @Test
    fun `diagram ids are stable across reparsing and line ending styles`() {
        val markdown = "Text\n\n```plantuml\nA -> B\n```"
        val first = parseHbMarkdown(markdown)
        assertEquals(first, parseHbMarkdown(markdown))
        val crlf = parseHbMarkdown(markdown.replace("\n", "\r\n")).single { it.kind == HbMarkdownBlockKind.Diagram }
        assertEquals("A -> B", crlf.content.text)
        assertEquals(first.single { it.kind == HbMarkdownBlockKind.Diagram }.id, crlf.id)
    }
}
