package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbDiagramUiTest {
    private val source = "@startuml\nA -> B : hello\n@enduml"
    private val block = parseHbMarkdown("```plantuml\n$source\n```").single()

    @Test
    fun `a drawn diagram opens and closes the full-size viewer`() = runSkikoComposeUiTest {
        val image = HbDiagramResult.Image(ImageBitmap(80, 40), DpSize(40.dp, 20.dp))
        show { image }
        onNodeWithTag(DIAGRAM_PREVIEW_TAG).assertExists()
        onNodeWithText(source).assertDoesNotExist()
        onNodeWithTag(DIAGRAM_PREVIEW_TAG).performClick()
        onNodeWithTag(DIAGRAM_VIEWER_TAG).assertExists()
        onNodeWithText("Close").performClick()
        onNodeWithTag(DIAGRAM_VIEWER_TAG).assertDoesNotExist()
        onNodeWithTag(DIAGRAM_PREVIEW_TAG).performClick()
        onNodeWithTag(DIAGRAM_VIEWER_TAG).performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag(DIAGRAM_VIEWER_TAG).assertDoesNotExist()
    }

    @Test
    fun `the viewer shows the source to read and copy`() = runSkikoComposeUiTest {
        show { HbDiagramResult.Image(ImageBitmap(80, 40), DpSize(40.dp, 20.dp)) }
        onNodeWithTag(DIAGRAM_PREVIEW_TAG).performClick()
        onNodeWithContentDescription("Copy source").assertExists()
        onNodeWithText("Show source").performClick()
        onNodeWithText(source).assertExists()
        onNodeWithText("Show diagram").performClick()
        onNodeWithText(source).assertDoesNotExist()
    }

    @Test
    fun `the focused viewer scrolls from the keyboard`() = runSkikoComposeUiTest(size = Size(800f, 600f)) {
        show { HbDiagramResult.Image(ImageBitmap(40, 40), DpSize(2000.dp, 2000.dp)) }
        onNodeWithTag(DIAGRAM_PREVIEW_TAG).performClick()
        val viewer = onNodeWithTag(DIAGRAM_VIEWER_TAG)
        viewer.requestFocus()
        viewer.performKeyInput { pressKey(Key.PageDown) }
        viewer.performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        val config = viewer.fetchSemanticsNode().config
        assertTrue(config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        assertTrue(config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f)
        viewer.performKeyInput { pressKey(Key.MoveHome) }
        waitForIdle()
        assertEquals(0f, viewer.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
    }

    @Test
    fun `a changed source never shows the previous image while drawing`() = runSkikoComposeUiTest {
        val pending = CompletableDeferred<HbDiagramResult>()
        var markdown by mutableStateOf("```plantuml\n$source\n```")
        val drawn = HbDiagramResult.Image(ImageBitmap(20, 20), DpSize(20.dp, 20.dp))
        val renderer = HbDiagramRenderer { request -> if (request.source == source) drawn else pending.await() }
        setContent {
            HbTheme {
                HbDiagramsProvider(renderer) { HbMarkdownBlockContent(parseHbMarkdown(markdown).single()) }
            }
        }
        onNodeWithTag(DIAGRAM_PREVIEW_TAG).assertExists()
        runOnIdle { markdown = "```plantuml\n@startuml\nB -> C\n@enduml\n```" }
        onNodeWithTag(DIAGRAM_PREVIEW_TAG).assertDoesNotExist()
        onNodeWithText("Rendering diagram…").assertExists()
    }

    @Test
    fun `a syntax error shows its line and message above the source`() = runSkikoComposeUiTest {
        show { HbDiagramResult.SyntaxError(line = 2, message = "Syntax Error?") }
        onNodeWithText("Diagram error · line 2: Syntax Error?").assertExists()
        onNodeWithText(source).assertExists()
        onNodeWithTag(DIAGRAM_PREVIEW_TAG).assertDoesNotExist()
    }

    @Test
    fun `a transient failure offers a retry that draws again`() = runSkikoComposeUiTest {
        var calls = 0
        show { if (++calls == 1) HbDiagramResult.Failed(HbDiagramFailure.Busy) else HbDiagramResult.Unsupported }
        onNodeWithText("Another diagram is still drawing.").assertExists()
        onNodeWithText("Retry").performClick()
        onNodeWithText("Another diagram is still drawing.").assertDoesNotExist()
        onNodeWithText(source).assertExists()
        assertEquals(2, calls)
    }

    @Test
    fun `an internal failure has no retry and never shows exception text`() = runSkikoComposeUiTest {
        show { error("renderer broke its contract") }
        onNodeWithText("The diagram could not be drawn.").assertExists()
        onNodeWithText("Retry").assertDoesNotExist()
        onNodeWithText(source).assertExists()
    }

    @Test
    fun `the source stays visible with a status while drawing`() = runSkikoComposeUiTest {
        val pending = CompletableDeferred<HbDiagramResult>()
        show { pending.await() }
        onNodeWithText("Rendering diagram…").assertExists()
        onNodeWithText(source).assertExists()
        pending.complete(HbDiagramResult.Unsupported)
        waitForIdle()
        onNodeWithText("Rendering diagram…").assertDoesNotExist()
    }

    @Test
    fun `a drawn image is reused when the row comes back`() = runSkikoComposeUiTest {
        var calls = 0
        var isShown by mutableStateOf(true)
        val renderer = HbDiagramRenderer {
            calls++
            HbDiagramResult.Image(ImageBitmap(20, 20), DpSize(20.dp, 20.dp))
        }
        setContent {
            HbTheme {
                HbDiagramsProvider(renderer) { if (isShown) HbMarkdownBlockContent(block) }
            }
        }
        onNodeWithTag(DIAGRAM_PREVIEW_TAG).assertExists()
        runOnIdle { isShown = false }
        runOnIdle { isShown = true }
        onNodeWithTag(DIAGRAM_PREVIEW_TAG).assertExists()
        assertTrue(calls == 1, "calls=$calls")
    }

    private fun SkikoComposeUiTest.show(render: suspend () -> HbDiagramResult) {
        val renderer = HbDiagramRenderer { render() }
        setContent { HbTheme { HbDiagramsProvider(renderer) { HbMarkdownBlockContent(block) } } }
    }
}
