package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.persistentListOf
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbPanelComposerUiTest {
    @Test
    fun `panel stays compact for one line grows for multiline input and scrolls at the height cap`() =
        runSkikoComposeUiTest(size = Size(1200f, 900f)) {
            var draft by mutableStateOf("")
            var edits = 0
            setContent {
                HbTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
                        HbChatComposer(
                            value = draft,
                            onValueChange = {
                                draft = it
                                edits++
                            },
                            onSend = {},
                            onStop = {},
                            sendLabel = "Send",
                            stopLabel = "Stop",
                            modifier = Modifier.testTag("composer"),
                            inputMaxHeight = 120.dp,
                            layout = HbComposerLayout.Panel,
                            placeholder = "Prompt",
                            leadingContent = { HbButton("Add", {}, style = HbButtonStyle.Ghost) },
                            trailingContent = { HbButton("Model", {}, style = HbButtonStyle.Ghost) },
                        )
                    }
                }
            }
            val editor = onNodeWithContentDescription("Prompt")
            val composer = onNodeWithTag("composer")
            val blankHeight = composer.fetchSemanticsNode().boundsInRoot.height
            assertTrue(blankHeight <= 96f, "The empty desktop composer should be a compact editor and toolbar")
            runOnIdle { draft = "First line" }
            assertEquals(blankHeight, composer.fetchSemanticsNode().boundsInRoot.height)
            runOnIdle { draft = "First line\nSecond line\nThird line" }
            assertTrue(composer.fetchSemanticsNode().boundsInRoot.height > blankHeight)
            val longDraft = (1..40).joinToString("\n") { "Line $it with distinct content" }
            runOnIdle { draft = longDraft }
            val editorBounds = editor.fetchSemanticsNode().boundsInRoot
            assertEquals(120f, editorBounds.height)
            val cappedHeight = composer.fetchSemanticsNode().boundsInRoot.height
            editor.performMouseInput { moveTo(center) }
            val beforeScroll = composerEditorPixels(captureToImage().toAwtImage(), editorBounds)
            editor.performMouseInput { scroll(3f) }
            waitForIdle()
            val afterScroll = composerEditorPixels(captureToImage().toAwtImage(), editorBounds)
            assertFalse(beforeScroll.contentEquals(afterScroll), "Overflowing draft should scroll inside its editor")
            assertEquals(cappedHeight, composer.fetchSemanticsNode().boundsInRoot.height)
            assertEquals(longDraft, editor.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            onNodeWithContentDescription("Send").assertIsDisplayed()
            runOnIdle { assertEquals(0, edits) }
        }

    @Test
    fun `panel toolbar stays below editor and keeps draft focus and send reachable while resizing`() =
        runSkikoComposeUiTest(size = Size(1200f, 900f)) {
            var draft by mutableStateOf("")
            var panelWidth by mutableStateOf(1000.dp)
            var sends = 0
            setContent {
                HbTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        HbChatComposer(
                            value = draft,
                            onValueChange = { draft = it },
                            onSend = { sends++ },
                            onStop = {},
                            sendLabel = "Send",
                            stopLabel = "Stop",
                            modifier = Modifier.width(panelWidth).testTag("composer"),
                            layout = HbComposerLayout.Panel,
                            placeholder = "Prompt",
                            leadingContent = {
                                HbComposerMenuButton(
                                    "Add",
                                    persistentListOf(HbComposerAction("file", "File")),
                                    false,
                                    {},
                                    {},
                                    icon = HbIcons.Plus,
                                    style = HbComposerMenuStyle.Circle,
                                )
                            },
                            trailingContent = { PanelTestPreferences() },
                        )
                    }
                }
            }
            val editor = onNodeWithContentDescription("Prompt")
            editor.performTextInput("Keep this draft")
            val wideEditor = editor.fetchSemanticsNode().boundsInRoot
            val wideModel = onNodeWithText("Model").fetchSemanticsNode().boundsInRoot
            assertTrue(wideModel.top >= wideEditor.bottom)
            runOnIdle { panelWidth = 400.dp }
            editor.assertIsFocused()
            val compactEditor = editor.fetchSemanticsNode().boundsInRoot
            val compactModel = onNodeWithText("Model").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(compactModel.top > compactEditor.bottom)
            val context = onNodeWithContentDescription("Add").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(compactModel.top >= context.bottom, "Narrow toolbar must separate context and model controls")
            assertEquals("Keep this draft", editor.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            onNodeWithContentDescription("Send").assertIsDisplayed().performClick()
            runOnIdle { assertEquals(1, sends) }
        }
}

@Composable
private fun PanelTestPreferences() {
    HbComposerMenuButton(
        "Deep",
        persistentListOf(HbComposerAction("high", "Deep")),
        false,
        {},
        {},
        style = HbComposerMenuStyle.AccentPill,
    )
    HbComposerMenuButton(
        "Model",
        persistentListOf(HbComposerAction("model", "Model")),
        false,
        {},
        {},
        style = HbComposerMenuStyle.Pill,
    )
}

/** Excludes the scrollbar and panel edges so a changed image proves text movement. */
private fun composerEditorPixels(image: BufferedImage, bounds: Rect): IntArray {
    val width = bounds.width.toInt() - 16
    val height = bounds.height.toInt() - 12
    return image.getRGB(bounds.left.toInt(), bounds.top.toInt(), width, height, null, 0, width)
}
