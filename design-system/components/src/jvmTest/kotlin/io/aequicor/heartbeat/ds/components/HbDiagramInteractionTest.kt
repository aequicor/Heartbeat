package io.aequicor.heartbeat.ds.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbDiagramInteractionTest {
    private val block = parseHbMarkdown("```plantuml\n@startuml\nA -> B\n@enduml\n```").single()
    private val destination = "https://example.com"
    private val image = HbDiagramResult.Image(
        ImageBitmap(1600, 1000).also {
            Canvas(it).drawRect(Rect(650f, 400f, 950f, 600f), Paint().apply { color = Color.Gray })
        },
        DpSize(1600.dp, 1000.dp),
        persistentListOf(HbDiagramLink(Rect(650f, 400f, 950f, 600f), destination, "Docs")),
    )

    @Test
    fun `viewer occupies the window in both themes and at compact widths`() {
        listOf(420f, 1280f).forEach { width ->
            listOf(false, true).forEach { dark ->
                runSkikoComposeUiTest(size = Size(width, 800f)) {
                    show(dark = dark)
                    onNodeWithTag(DIAGRAM_PREVIEW_TAG).performClick()
                    val window = onNodeWithTag("diagram-window")
                    val bounds = window.fetchSemanticsNode().boundsInRoot
                    assertEquals(width, bounds.width, 1f)
                    assertEquals(800f, bounds.height, 1f)
                    assertTrue(onNodeWithTag(DIAGRAM_VIEWER_TAG).fetchSemanticsNode().boundsInRoot.height > 400f)
                    val bitmap = window.captureToImage().asSkiaBitmap()
                    val file = File("build/diagram-previews/viewer-${width.toInt()}-$dark.png")
                    file.parentFile.mkdirs()
                    file.writeBytes(checkNotNull(Image.makeFromBitmap(bitmap).encodeToData()).bytes)
                }
            }
        }
    }

    @Test
    fun `dragging a link pans without opening it and a subsequent click still hits after zoom`() =
        runSkikoComposeUiTest(size = Size(800f, 600f)) {
            val opened = mutableListOf<String>()
            show(onLink = { opened += it })
            onNodeWithTag(DIAGRAM_PREVIEW_TAG).performClick()
            onNodeWithText("100%").performClick()
            val target = onNodeWithTag("diagram-link-0")
            target.performMouseInput {
                moveTo(center)
                press()
                moveBy(Offset(50f, 35f))
                release()
            }
            assertTrue(opened.isEmpty())
            onNodeWithTag(DIAGRAM_VIEWER_TAG).performMouseInput {
                moveTo(center)
                scroll(-1f)
            }
            onNodeWithTag("diagram-link-0").performMouseInput {
                moveTo(center)
                press()
                release()
            }
            assertEquals(listOf(destination), opened)
        }

    @Test
    fun `keyboard works immediately and transform survives source toggle`() = runSkikoComposeUiTest {
        show()
        onNodeWithTag(DIAGRAM_PREVIEW_TAG).performClick()
        onNodeWithTag(DIAGRAM_VIEWER_TAG).performKeyInput { pressKey(Key.Equals) }
        val zoomed = onNodeWithTag("diagram-link-0").fetchSemanticsNode().boundsInRoot
        onNodeWithText("Show source").performClick()
        onNodeWithText("Show diagram").performClick()
        assertEquals(zoomed, onNodeWithTag("diagram-link-0").fetchSemanticsNode().boundsInRoot)
        onNodeWithTag(DIAGRAM_VIEWER_TAG).performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag(DIAGRAM_VIEWER_TAG).assertDoesNotExist()
    }

    @Test
    fun `source accepts page keys immediately after opening`() = runSkikoComposeUiTest(size = Size(800f, 600f)) {
        val source = "@startuml\n" + (1..100).joinToString("\n") { "A -> B : $it" } + "\n@enduml"
        setContent {
            HbTheme {
                HbDiagramsProvider(HbDiagramRenderer { image }) { HbMarkdown("```plantuml\n$source\n```") }
            }
        }
        onNodeWithTag(DIAGRAM_PREVIEW_TAG).performClick()
        onNodeWithText("Show source").performClick()
        val sourceNode = onNodeWithTag("diagram-source")
        sourceNode.performKeyInput { pressKey(Key.PageDown) }
        waitForIdle()
        assertTrue(sourceNode.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
    }

    @Test
    fun `diagram action activates once by keyboard and closes viewer`() = runSkikoComposeUiTest {
        val actions = mutableListOf<HbDiagramAction>()
        show(url = "heartbeat://diagram/insert?text=Hello%20chat", onAction = { actions += it })
        onNodeWithTag(DIAGRAM_PREVIEW_TAG).performClick()
        onNodeWithTag(DIAGRAM_VIEWER_TAG).performKeyInput {
            pressKey(Key.Tab)
            pressKey(Key.Enter)
        }
        assertEquals(listOf(HbDiagramAction(HbDiagramActionKind.Insert, "Hello chat")), actions)
        onNodeWithTag(DIAGRAM_VIEWER_TAG).assertDoesNotExist()
    }

    @Test
    fun `without chat handler action does not leak to external link callback`() = runSkikoComposeUiTest {
        val opened = mutableListOf<String>()
        show(url = "heartbeat://diagram/send?text=Hello", onLink = { opened += it })
        onNodeWithTag(DIAGRAM_PREVIEW_TAG).performClick()
        onNodeWithTag("diagram-link-0").performMouseInput {
            moveTo(center)
            press()
            release()
        }
        assertTrue(opened.isEmpty())
        onNodeWithTag(DIAGRAM_VIEWER_TAG).assertExists()
    }

    private fun SkikoComposeUiTest.show(
        dark: Boolean = false,
        url: String = destination,
        onLink: (String) -> Unit = {},
        onAction: ((HbDiagramAction) -> Unit)? = null,
    ) {
        val drawn = image.copy(links = persistentListOf(image.links.first().copy(destination = url)))
        setContent {
            HbTheme(darkTheme = dark) {
                HbDiagramsProvider(HbDiagramRenderer { drawn }) {
                    HbDiagramActionsProvider(onAction) { HbMarkdownBlockContent(block, onLinkClick = onLink) }
                }
            }
        }
    }
}
