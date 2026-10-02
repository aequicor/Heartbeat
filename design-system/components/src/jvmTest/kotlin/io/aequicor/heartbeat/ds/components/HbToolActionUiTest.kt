package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HbToolActionUiTest {
    private val worktree = HbToolCall(
        id = "worktree:chat",
        title = "Task completed",
        summary = "Local merge target: master",
        blocks = persistentListOf(HbToolBlock.Console("build-output", "BUILD FAILED")),
        kind = HbToolKind.Worktree,
        actions = persistentListOf(
            HbToolAction("create-pr", "Create PR", HbButtonStyle.Primary),
            HbToolAction("leave", "Leave as is", HbButtonStyle.Ghost),
        ),
    )

    @Test
    fun `host entry actions stay reachable while collapsed and report their call`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val pressed = mutableListOf<Pair<String, String>>()
            val entry = HbChatMessage(
                id = "worktree:chat",
                author = "Heartbeat",
                text = "",
                role = HbChatRole.System,
                kind = HbMessageKind.Tool,
                appearance = HbMessageAppearance(widthFraction = 1f, isUnified = true),
                parts = persistentListOf(HbMessagePart.Tool(worktree)),
            )
            val timeline = HbChatTimeline.from(HbChatSection("day", ""), persistentListOf(entry))
            setContent {
                HbTheme(darkTheme = false) {
                    HbChatTranscript(
                        timeline,
                        Modifier.fillMaxSize(),
                        onToolAction = { call, action -> pressed += call.id to action.id },
                    )
                }
            }
            onNodeWithText("Heartbeat").assertDoesNotExist()
            onNodeWithTag("message-footer:worktree:chat").assertDoesNotExist()
            onNodeWithText("Local merge target: master").assertIsDisplayed()
            onNodeWithText("BUILD FAILED").assertDoesNotExist()
            onNodeWithTag("tool-action:worktree:chat:create-pr").performClick()
            onNodeWithTag("tool-action:worktree:chat:leave").performClick()
            runOnIdle { assertEquals(listOf("worktree:chat" to "create-pr", "worktree:chat" to "leave"), pressed) }

            onNodeWithTag("tool:worktree:chat").performClick()
            onAllNodesWithTag("tool-block:build-output").assertCountEquals(1)
            onNodeWithText("BUILD FAILED").assertIsDisplayed()
            onNodeWithTag("tool-action:worktree:chat:create-pr").assertIsDisplayed()
        }

    @Test
    fun `legacy transcript overload reports tool actions`() = runSkikoComposeUiTest(size = Size(900f, 700f)) {
        val pressed = mutableListOf<Pair<String, String>>()
        val entry = HbChatMessage(
            id = "worktree:chat",
            author = "Heartbeat",
            text = "",
            role = HbChatRole.System,
            kind = HbMessageKind.Tool,
            parts = persistentListOf(HbMessagePart.Tool(worktree)),
        )
        setContent {
            HbTheme(darkTheme = false) {
                HbChatTranscript(
                    messages = persistentListOf(entry),
                    modifier = Modifier.fillMaxSize(),
                    onToolAction = { call, action -> pressed += call.id to action.id },
                )
            }
        }
        onNodeWithTag("tool-action:worktree:chat:leave").performClick()
        runOnIdle { assertEquals(listOf("worktree:chat" to "leave"), pressed) }
    }

    @Test
    fun `worktree card without blocks offers no disclosure`() = runSkikoComposeUiTest(size = Size(900f, 700f)) {
        val pressed = mutableListOf<String>()
        val expanded = mutableListOf<Boolean>()
        val call = worktree.copy(blocks = persistentListOf())
        setContent {
            HbTheme(darkTheme = false) {
                HbColumn(Modifier.fillMaxSize(), gap = HbTheme.spacing.l) {
                    // A stale expanded key of a card whose output went away must not reopen an empty disclosure.
                    HbToolCallHeader(
                        call,
                        isExpanded = true,
                        onExpandedChange = { expanded += it },
                        isUnified = true,
                        onAction = { pressed += it.id },
                    )
                    HbToolCallView(
                        call.copy(id = "worktree:standalone"),
                        isExpanded = true,
                        onAction = { pressed += it.id },
                    )
                }
            }
        }
        listOf("tool:worktree:chat", "tool:worktree:standalone").forEach { tag ->
            onNodeWithTag(tag).assertHasNoClickAction()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.RequestFocus))
                .assertTextContains("Task completed")
        }
        // The unified row reads its title, summary and status as one node; the actions stay separate controls.
        onNodeWithTag("tool:worktree:chat").assertTextContains("Local merge target: master")
            .assertTextContains("Complete")
        onNodeWithTag("tool-action:worktree:chat:create-pr")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus))
            .performClick()
        onNodeWithTag("tool-action:worktree:standalone:leave").performClick()
        runOnIdle {
            assertEquals(listOf("create-pr", "leave"), pressed)
            assertEquals(emptyList(), expanded)
        }
    }

    @Test
    fun `standalone disclosure offers the same actions`() = runSkikoComposeUiTest {
        val pressed = mutableListOf<String>()
        setContent {
            HbTheme(darkTheme = true) {
                HbToolCallView(worktree, onAction = { pressed += it.id })
            }
        }
        onNodeWithTag("tool-action:worktree:chat:leave").performClick()
        runOnIdle { assertEquals(listOf("leave"), pressed) }
    }
}
