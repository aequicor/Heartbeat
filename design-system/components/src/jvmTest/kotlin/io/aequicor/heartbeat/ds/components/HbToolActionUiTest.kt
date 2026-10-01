package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
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
            onNodeWithTag("create-pr").performClick()
            onNodeWithTag("leave").performClick()
            runOnIdle { assertEquals(listOf("worktree:chat" to "create-pr", "worktree:chat" to "leave"), pressed) }

            onNodeWithTag("worktree:chat").performClick()
            onAllNodesWithTag("build-output").assertCountEquals(1)
            onNodeWithText("BUILD FAILED").assertIsDisplayed()
            onNodeWithTag("create-pr").assertIsDisplayed()
        }

    @Test
    fun `standalone disclosure offers the same actions`() = runSkikoComposeUiTest {
        val pressed = mutableListOf<String>()
        setContent {
            HbTheme(darkTheme = true) {
                HbToolCallView(worktree, onAction = { pressed += it.id })
            }
        }
        onNodeWithTag("leave").performClick()
        runOnIdle { assertEquals(listOf("leave"), pressed) }
    }
}
