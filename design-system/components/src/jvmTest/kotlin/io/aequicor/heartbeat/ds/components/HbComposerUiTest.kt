package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbComposerUiTest {
    @Test
    fun `Shift Enter inserts newline while Enter Ctrl and Command Enter each send without clearing the draft`() =
        runSkikoComposeUiTest(size = Size(620f, 400f)) {
            var draft by mutableStateOf("")
            var sends = 0
            setContent {
                ComposerTestHost {
                    HbChatComposer(draft, { draft = it }, { sends++ }, {}, "Send", "Stop", placeholder = "Prompt")
                }
            }
            val editor = onNodeWithContentDescription("Prompt")
            editor.performTextInput("First line")
            editor.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
            runOnIdle {
                assertEquals("First line\n", draft)
                assertEquals(0, sends)
            }
            editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
            runOnIdle { assertEquals(1, sends) }
            editor.performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Enter) } }
            runOnIdle {
                assertEquals(2, sends)
                assertEquals("First line\n", draft)
            }
            editor.performKeyInput { pressKey(Key.Enter) }
            runOnIdle {
                assertEquals(3, sends)
                assertEquals("First line\n", draft)
            }
            saveComposerPreview("editor", captureToImage().toAwtImage())
        }

    @Test
    fun `blank draft cannot send and streaming exposes only stop while disabled blocks input and actions`() =
        runSkikoComposeUiTest(size = Size(620f, 400f)) {
            var draft by mutableStateOf("   ")
            var isStreaming by mutableStateOf(false)
            var isEnabled by mutableStateOf(true)
            var sends = 0
            var stops = 0
            setContent {
                ComposerTestHost {
                    HbChatComposer(
                        draft,
                        { draft = it },
                        { sends++ },
                        { stops++ },
                        "Send",
                        "Stop",
                        placeholder = "Prompt",
                        isStreaming = isStreaming,
                        enabled = isEnabled,
                    )
                }
            }
            onNodeWithContentDescription("Send").assertIsNotEnabled()
            runOnIdle {
                draft = "Keep this draft"
                isStreaming = true
            }
            onNodeWithContentDescription("Send").assertDoesNotExist()
            onNodeWithContentDescription("Prompt").performSemanticsAction(SemanticsActions.RequestFocus)
            onNodeWithContentDescription("Prompt").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
            runOnIdle { assertEquals(0, sends) }
            onNodeWithContentDescription("Stop").performClick()
            runOnIdle {
                assertEquals(1, stops)
                isEnabled = false
            }
            onNodeWithContentDescription("Prompt").assertIsNotEnabled()
            onNodeWithContentDescription("Stop").assertIsNotEnabled().performMouseInput {
                moveTo(center)
                press()
                release()
            }
            runOnIdle {
                assertEquals(0, sends)
                assertEquals(1, stops)
            }
        }

    @Test
    fun `menu keyboard skips disabled actions scrolls to last item and Escape restores trigger focus`() =
        runSkikoComposeUiTest(size = Size(620f, 700f)) {
            var isExpanded by mutableStateOf(false)
            val chosen = mutableListOf<String>()
            val actions = buildList {
                add(HbComposerAction("files", "Files", "Add project context"))
                add(HbComposerAction("disabled", "Unavailable", isEnabled = false))
                repeat(12) { add(HbComposerAction("action-$it", "Command $it", "A localized description")) }
            }.toImmutableList()
            setContent {
                ComposerTestHost {
                    HbChatComposer("", {}, {}, {}, "Send", "Stop", placeholder = "Prompt", leadingContent = {
                        HbComposerMenuButton(
                            label = "+",
                            actions = actions,
                            isExpanded = isExpanded,
                            onExpandedChange = { isExpanded = it },
                            onAction = { chosen += it },
                            accessibleLabel = "Add",
                            icon = HbIcons.Plus,
                            headerLabel = "Add context",
                        )
                    })
                }
            }
            onNodeWithContentDescription("Add").performSemanticsAction(SemanticsActions.RequestFocus)
            onNodeWithContentDescription("Add").performKeyInput { pressKey(Key.DirectionDown) }
            onNodeWithText("Files").assertIsFocused()
            onNodeWithText("Files").performKeyInput { pressKey(Key.DirectionDown) }
            onNodeWithText("Command 0").assertIsFocused()
            onNodeWithText("Command 0").performKeyInput { pressKey(Key.MoveEnd) }
            onNodeWithText("Command 11").assertIsFocused().assertIsDisplayed()
            saveComposerPreview("menu-overflow", captureToImage().toAwtImage())
            onNodeWithText("Command 11").performKeyInput { pressKey(Key.Escape) }
            onNodeWithText("Command 11").assertDoesNotExist()
            onNodeWithContentDescription("Add").assertIsFocused()
            runOnIdle {
                assertFalse(isExpanded)
                assertTrue(chosen.isEmpty())
            }
        }

    @Test
    fun `choosing a command fires once and dismisses and outside click dismisses without activating background`() =
        runSkikoComposeUiTest(size = Size(620f, 700f)) {
            var isExpanded by mutableStateOf(false)
            var chosen = 0
            var backgroundClicks = 0
            val actions = persistentListOf(HbComposerAction("files", "Files"))
            setContent {
                HbTheme(darkTheme = false) {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        HbButton("Outside", { backgroundClicks++ }, modifier = Modifier.align(Alignment.TopEnd))
                        HbChatComposer(
                            "", {}, {}, {}, "Send", "Stop",
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                            placeholder = "Prompt",
                            leadingContent = {
                                HbComposerMenuButton(
                                    label = "+",
                                    actions = actions,
                                    isExpanded = isExpanded,
                                    onExpandedChange = { isExpanded = it },
                                    onAction = { chosen++ },
                                    accessibleLabel = "Add",
                                    icon = HbIcons.Plus,
                                )
                            },
                        )
                    }
                }
            }
            onNodeWithContentDescription("Add").performClick()
            onNodeWithText("Files").performKeyInput { pressKey(Key.Enter) }
            runOnIdle {
                assertEquals(1, chosen)
                assertFalse(isExpanded)
            }
            onNodeWithContentDescription("Add").performClick()
            onNodeWithText("Outside").performMouseInput {
                moveTo(center)
                press()
                release()
            }
            runOnIdle {
                assertFalse(isExpanded)
                assertEquals(0, backgroundClicks)
                assertEquals(1, chosen)
            }
        }

    @Test
    fun `short compact window bounds long drafts and keeps send visible with overflowing toolbar`() =
        runSkikoComposeUiTest(size = Size(320f, 360f)) {
            var sends = 0
            setContent {
                ComposerTestHost {
                    HbChatComposer(
                        value = "Long line\n".repeat(100),
                        onValueChange = {},
                        onSend = { sends++ },
                        onStop = {},
                        sendLabel = "Send",
                        stopLabel = "Stop",
                        modifier = Modifier.testTag("composer"),
                        placeholder = "Prompt",
                        inputMaxHeight = 80.dp,
                        leadingContent = { HbButton("+", {}, style = HbButtonStyle.Ghost) },
                        trailingContent = {
                            HbButton("A deliberately long model name", {}, style = HbButtonStyle.Ghost)
                            HbButton("Reasoning mode", {}, style = HbButtonStyle.Ghost)
                        },
                    )
                }
            }
            onNodeWithContentDescription("Send").assertIsDisplayed().performClick()
            val editorBounds = onNodeWithContentDescription("Prompt").fetchSemanticsNode().boundsInRoot
            val composerBounds = onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot
            assertTrue(editorBounds.height <= 80f)
            assertTrue(composerBounds.height < 240f)
            runOnIdle { assertEquals(1, sends) }
            saveComposerPreview("compact-long-draft", captureToImage().toAwtImage())
        }
}

@Composable
private fun ComposerTestHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    HbTheme(darkTheme = false) {
        Box(modifier = modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) { content() }
    }
}

private fun saveComposerPreview(name: String, image: BufferedImage) {
    val directory = File("build/previews")
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create preview directory" }
    check(ImageIO.write(image, "png", File(directory, "composer-$name.png"))) { "PNG encoder is unavailable" }
}
