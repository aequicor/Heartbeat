package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbMotion
import kotlinx.collections.immutable.persistentListOf
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A hovered hint is composed in a popup layer that inherits the selection registrar of the message row, while its
 * layout belongs to another scene. Registering there made the next press inside the row compare two hierarchies and
 * crash with "layouts are not part of the same hierarchy", so a disclosure was unsafe to toggle with the mouse.
 */
@OptIn(ExperimentalTestApi::class)
class HbReasoningDisclosureSelectionUiTest {
    private val reasoningTitle =
        "Exposed reasoning about the exposed session engine contract, its payloads and approvals"

    @Test
    fun `a hovered hint over a clipped disclosure title keeps the row press and toggle alive`() =
        runSkikoComposeUiTest(size = Size(300f, 200f)) {
            var isExpanded by mutableStateOf(false)
            setContent {
                HbTheme(darkTheme = false, motion = HbMotion(tooltipDelayMillis = 0)) {
                    SelectionContainer(Modifier.testTag("row")) {
                        HbUnifiedToolHeader(
                            call = reasoning(),
                            isExpanded = isExpanded,
                            onExpandedChange = { isExpanded = it },
                        )
                    }
                }
            }
            val title = onNodeWithText(reasoningTitle, useUnmergedTree = true)
            val titleBounds = title.fetchSemanticsNode().boundsInRoot
            title.performMouseInput { moveTo(center) }
            awaitTooltip(reasoningTitle)
            onNodeWithTag("row").performMouseInput {
                moveTo(Offset(titleBounds.center.x, titleBounds.center.y))
                press()
                release()
            }
            runOnIdle { assertTrue(isExpanded, "The press under a visible hint must toggle the disclosure") }
        }

    @Test
    fun `reasoning disclosure toggles under a hovered hint while transcript selection stays usable`() =
        runSkikoComposeUiTest(size = Size(560f, 420f)) {
            var timeline by mutableStateOf(transcript())
            val clipboard = ReasoningSelectionClipboard()
            setContent {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    HbTheme(darkTheme = false, motion = HbMotion(tooltipDelayMillis = 0)) {
                        HbChatTranscript(
                            timeline,
                            Modifier.fillMaxSize().testTag("transcript"),
                            toolExpansionState = HbToolExpansionState(),
                        )
                    }
                }
            }

            fun toggleDisclosure() {
                val title = onNodeWithText(reasoningTitle, useUnmergedTree = true)
                val bounds = title.fetchSemanticsNode().boundsInRoot
                title.performMouseInput { moveTo(center) }
                awaitTooltip(reasoningTitle)
                onNodeWithTag("transcript").performMouseInput {
                    moveTo(Offset(bounds.center.x, bounds.center.y))
                    press()
                    release()
                }
                mainClock.advanceTimeBy(400)
            }

            onNodeWithText("Before inspection").assertIsDisplayed()
            onNodeWithText("Inspect the exposed contract").assertDoesNotExist()
            toggleDisclosure()
            onNodeWithText("Inspect the exposed contract").assertIsDisplayed()
            val intro = onNodeWithText("Before inspection")
            intro.performMouseInput {
                moveTo(Offset(1f, center.y))
                press()
                advanceEventTime(40)
                moveTo(Offset(width - 1f, center.y))
                advanceEventTime(40)
                release()
            }
            intro.performKeyInput { pressKey(Key.Copy) }
            runOnIdle { assertEquals("Before inspection", clipboard.text(), "Mouse selection must stay usable") }
            toggleDisclosure()
            onNodeWithText("Inspect the exposed contract").assertDoesNotExist()
            runOnIdle { timeline = timeline.replaceLatest(timeline.latestMessage!!.copy(text = "Streamed tail")) }
            onNodeWithText("Before inspection").assertIsDisplayed()
        }

    /** Waits until the hint of the hovered title is composed next to it. */
    private fun ComposeUiTest.awaitTooltip(title: String) {
        waitUntil { onAllNodesWithText(title, useUnmergedTree = true).fetchSemanticsNodes().size > 1 }
    }

    private fun reasoning() = HbToolCall(
        id = "reason",
        title = reasoningTitle,
        summary = "Comparing the public API",
        blocks = persistentListOf(HbToolBlock.Markdown("plan", "Inspect the exposed contract")),
        kind = HbToolKind.Reasoning,
    )

    private fun transcript(): HbChatTimeline {
        val answer = HbChatMessage(
            id = "reply",
            author = "Heartbeat",
            text = "Before inspection\n\nAfter inspection",
            appearance = HbMessageAppearance(isUnified = true, widthFraction = 1f),
            parts = persistentListOf(
                HbMessagePart.Text("intro", "Before inspection"),
                HbMessagePart.Tool(reasoning()),
                HbMessagePart.Text("final", "After inspection"),
            ),
        )
        return HbChatTimeline.from(HbChatSection("today", "Today", isDate = true), persistentListOf(answer))
    }
}

private class ReasoningSelectionClipboard : Clipboard {
    private var entry: ClipEntry? = null

    override suspend fun getClipEntry(): ClipEntry? = entry

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        entry = clipEntry
    }

    @OptIn(ExperimentalComposeUiApi::class)
    fun text(): String? = entry?.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor) as? String
}
