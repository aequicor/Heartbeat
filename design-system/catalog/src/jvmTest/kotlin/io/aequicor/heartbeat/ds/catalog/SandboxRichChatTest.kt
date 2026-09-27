package io.aequicor.heartbeat.ds.catalog

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SandboxRichChatTest {
    @Test
    fun `composer menu dismisses and inserts markdown and expandable tool examples`() = runSkikoComposeUiTest(
        size = Size(1280f, 900f),
    ) {
        setContent { UIKitSandboxApp(initialDarkTheme = false) }
        onNodeWithText("EN").performClick()
        onNodeWithText("Start fresh").performClick()

        onNodeWithContentDescription("Add context").performClick()
        onNodeWithText("Markdown example").assertIsDisplayed()
        onNodeWithText("Markdown example").performKeyInput { pressKey(Key.Escape) }
        onNodeWithText("Markdown example").assertDoesNotExist()
        onNodeWithContentDescription("Add context").assertIsDisplayed()

        val markdownParagraphs = onAllNodesWithText("Try + below", substring = true)
        val originalParagraphIds = markdownParagraphs.fetchSemanticsNodes().map { it.id }.toSet()
        onNodeWithContentDescription("Add context").performClick()
        onNodeWithText("Markdown example").performClick()
        onNodeWithText("Markdown example").assertDoesNotExist()
        assertTrue(
            markdownParagraphs.fetchSemanticsNodes().withIndex().any { (index, paragraph) ->
                paragraph.id !in originalParagraphIds && markdownParagraphs[index].isDisplayed()
            },
            "Adding a Markdown example must reveal a new paragraph, even when the same seed text remains composed.",
        )

        onNodeWithContentDescription("Add context").performClick()
        onNodeWithText("Tool result").performClick()
        val closedTools = onAllNodesWithText("workspace.inspect")
        closedTools[closedTools.fetchSemanticsNodes().lastIndex].assertIsDisplayed().performClick()
        val expandedTool = hasText("workspace.inspect") and
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapse tool")
        onNode(expandedTool).assertIsDisplayed().performClick()
        onNode(expandedTool).assertDoesNotExist()
        onNodeWithContentDescription("Send").assertIsDisplayed()
    }

    @Test
    fun `long session menu loads virtual history with sticky session headings`() = runSkikoComposeUiTest(
        size = Size(1280f, 900f),
    ) {
        setContent { UIKitSandboxApp(initialDarkTheme = false) }
        onNodeWithText("EN").performClick()
        onNodeWithContentDescription("Add context").performClick()
        onNodeWithText("Long session").performClick()
        waitUntil(timeoutMillis = 30000) {
            val trigger = onNodeWithContentDescription("Add context").fetchSemanticsNode()
            !trigger.config.contains(SemanticsProperties.Disabled)
        }
        onNode(hasScrollToIndexAction()).performScrollToIndex(9000)
        onNodeWithText("Earlier session · 90").assertIsDisplayed()
        val headerTop = onNodeWithText("Earlier session · 90").fetchSemanticsNode().boundsInRoot.top
        onNode(hasScrollToIndexAction()).performScrollToIndex(9003)
        val movedHeaderTop = onNodeWithText("Earlier session · 90").fetchSemanticsNode().boundsInRoot.top
        assertTrue(abs(movedHeaderTop - headerTop) < 2f)
        onNodeWithText("Jump to latest").assertIsDisplayed().performClick()
        onNodeWithText("Today · Studio").assertIsDisplayed()
        onNodeWithContentDescription("Send").assertIsDisplayed()
    }

    @Test
    fun `compact composer keeps send reachable after context menu and a multiline draft`() = runSkikoComposeUiTest(
        size = Size(390f, 520f),
    ) {
        setContent { UIKitSandboxApp(initialDarkTheme = false) }
        if (onAllNodesWithText("EN").fetchSemanticsNodes().isNotEmpty()) onNodeWithText("EN").performClick()
        onNodeWithContentDescription("Add context").performClick()
        onNodeWithText("Markdown example").assertIsDisplayed()
        onNodeWithText("Markdown example").performKeyInput { pressKey(Key.Escape) }
        onNode(hasSetTextAction()).performTextInput("A compact multiline draft\n".repeat(12))
        onNodeWithContentDescription("Send").assertIsDisplayed().assertIsEnabled().performClick()
        onNodeWithContentDescription("Stop").assertIsDisplayed().performClick()
        onNodeWithContentDescription("Send").assertIsDisplayed()
    }
}
