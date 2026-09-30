package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HbToolCallUiTest {
    @Test
    fun `expanded tool result survives lazy disposal`() = runSkikoComposeUiTest(
        size = Size(640f, 440f),
    ) {
        setContent {
            HbTheme(darkTheme = false) {
                LazyColumn(modifier = Modifier.fillMaxSize().testTag("tools")) {
                    items(count = 100, key = { "tool-$it" }) { index ->
                        HbToolCallView(
                            toolCall = HbToolCall(
                                id = "tool-$index",
                                title = "Inspect output $index",
                                blocks = persistentListOf(HbToolBlock.Console("output", "Saved result $index")),
                            ),
                        )
                    }
                }
            }
        }
        onNodeWithText("Saved result 0").assertDoesNotExist()
        onNodeWithText("Inspect output 0").performClick()
        onNodeWithText("Saved result 0").assertIsDisplayed()
        onNodeWithTag("tools").performScrollToIndex(70)
        onNodeWithText("Inspect output 70").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithText("Saved result 0").assertDoesNotExist()
        onNodeWithTag("tools").performScrollToIndex(0)
        onNodeWithText("Saved result 0").assertIsDisplayed()
        onNodeWithText("Inspect output 0").performClick()
        onNodeWithText("Saved result 0").assertDoesNotExist()
    }

    @Test
    fun `controlled disclosure reports requested state without changing external state`() = runSkikoComposeUiTest {
        var isExpansionRequested: Boolean? = null
        setContent {
            HbTheme(darkTheme = false) {
                HbToolCallView(
                    toolCall = HbToolCall(
                        "tool",
                        "Inspect",
                        blocks = persistentListOf(HbToolBlock.Console("log", "Payload")),
                    ),
                    isExpanded = false,
                    onExpandedChange = { isExpansionRequested = it },
                )
            }
        }
        onNodeWithText("Inspect").performClick()
        runOnIdle { assertEquals(true, isExpansionRequested) }
        onNodeWithText("Payload").assertDoesNotExist()
    }
}
