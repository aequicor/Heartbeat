package io.aequicor.heartbeat.ds.components

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HbDiagramFallbackUiTest {
    @Test
    fun `a diagram row without a renderer shows its whole source as code`() = runSkikoComposeUiTest {
        val source = (listOf("@startuml") + (1..30).map { "A -> B : step $it" } + "@enduml").joinToString("\n")
        val block = parseHbMarkdown("```plantuml\n$source\n```").single()
        assertEquals(HbMarkdownBlockKind.Diagram, block.kind)
        setContent { HbTheme { HbMarkdownBlockContent(block) } }
        onNodeWithText(source).assertExists()
        onNodeWithText("plantuml").assertExists()
    }
}
