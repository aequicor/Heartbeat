package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbColors
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbConsoleSelectionUiTest {
    @Test
    fun `console mouse selection persists after release and clears in standalone and transcript rows`() =
        runSkikoComposeUiTest(size = Size(620f, 300f)) {
            val source = "Selectable console output remains available\nBUILD SUCCESSFUL"
            val console = chunkHbConsole(source).single()
            val row = HbToolDisplayRow("console", text = source, console = console)
            var isTranscript by mutableStateOf(false)
            setContent { ConsoleSelectionHost(row, isTranscript) }
            listOf(false, true).forEach { transcript ->
                runOnIdle { isTranscript = transcript }
                val node = onNodeWithText(source)
                val idle = captureToImage().toAwtImage()
                node.performMouseInput {
                    moveTo(Offset(3f, 7f))
                    press()
                    advanceEventTime(40)
                    moveTo(Offset(120f, 7f))
                    advanceEventTime(40)
                    release()
                }
                mainClock.advanceTimeBy(1000)
                val selected = captureToImage().toAwtImage()
                val editorHighlight = HbColors.DesktopLight.selectionHighlight.toArgb()
                val pixels = selected.getRGB(0, 0, selected.width, selected.height, null, 0, selected.width)
                assertFalse(
                    pixels.any { it == editorHighlight },
                    "A dark console must not inherit the opaque light editor selection",
                )
                assertFalse(consolePixelsEqual(idle, selected), "Selection must persist after the pointer is released")
                saveConsoleSelectionPreview(if (transcript) "transcript" else "standalone", selected)
                node.performMouseInput {
                    moveTo(Offset(3f, 7f))
                    press()
                    release()
                }
                mainClock.advanceTimeBy(1000)
                assertTrue(consolePixelsEqual(idle, captureToImage().toAwtImage()), "A click must clear selection")
            }
        }
}

@Composable
private fun ConsoleSelectionHost(row: HbToolDisplayRow, isTranscript: Boolean, modifier: Modifier = Modifier) {
    HbTheme(darkTheme = false) {
        Box(modifier.fillMaxSize().background(HbTheme.colors.background).padding(16.dp)) {
            if (isTranscript) {
                HbTranscriptChunkContent(
                    chunk = HbTranscriptChunk("agent", "console", HbTranscriptBody.ToolPayload(row), true, true),
                    message = HbChatMessage("agent", "Agent", "", appearance = HbMessageAppearance(widthFraction = 1f)),
                    streamingLabel = "Streaming",
                    toolLabels = HbToolLabels(),
                    onLinkClick = null,
                )
            } else {
                HbToolPayloadRow(row)
            }
        }
    }
}

private fun consolePixelsEqual(first: BufferedImage, second: BufferedImage): Boolean =
    first.getRGB(0, 0, first.width, first.height, null, 0, first.width).contentEquals(
        second.getRGB(0, 0, second.width, second.height, null, 0, second.width),
    )

private fun saveConsoleSelectionPreview(name: String, image: BufferedImage) {
    val directory = File("build/previews")
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create preview directory" }
    check(ImageIO.write(image, "png", File(directory, "console-selection-$name.png"))) { "PNG encoder is unavailable" }
}
