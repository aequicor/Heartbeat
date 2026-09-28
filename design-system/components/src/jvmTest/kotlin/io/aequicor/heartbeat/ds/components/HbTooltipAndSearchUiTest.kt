package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbTooltipAndSearchUiTest {
    @Test
    fun `icon tooltip appears on hover and dismisses without blocking the action`() =
        runSkikoComposeUiTest(size = Size(360f, 180f)) {
            var clicks = 0
            setContent {
                HbTheme(motion = HbMotion(tooltipDelayMillis = 0)) {
                    HbIconButton(HbIcons.Settings, "Preferences", { clicks++ }, Modifier.padding(24.dp))
                }
            }
            val action = onNodeWithContentDescription("Preferences")
            action.performMouseInput { moveTo(center) }
            waitUntil { onAllNodesWithText("Preferences").fetchSemanticsNodes().isNotEmpty() }
            action.performMouseInput {
                press()
                release()
                exit()
            }
            runOnIdle { assertEquals(1, clicks) }
            onAllNodesWithText("Preferences").assertCountEquals(0)
        }

    @Test
    fun `search clear preserves the controlled input and accessible label`() =
        runSkikoComposeUiTest(size = Size(420f, 180f)) {
            var value by mutableStateOf("")
            setContent {
                HbTheme {
                    HbSearchField(
                        value,
                        { value = it },
                        "Find a conversation",
                        "Clear search",
                        Modifier.padding(16.dp).width(300.dp),
                        shortcutLabel = "⌘K",
                    )
                }
            }
            onNodeWithContentDescription("Find a conversation").performTextInput("Long studio title")
            runOnIdle { assertEquals("Long studio title", value) }
            onNodeWithContentDescription("Clear search").performClick()
            runOnIdle { assertEquals("", value) }
            onAllNodesWithText("⌘K").assertCountEquals(1)
        }

    @Test
    fun `secondary row click opens only the existing context action`() = runSkikoComposeUiTest {
        var activations = 0
        var menus = 0
        setContent {
            HbTheme {
                HbNavigationItem(
                    "Conversation",
                    { activations++ },
                    Modifier.width(280.dp).testTag("row"),
                    onSecondaryClick = { menus++ },
                )
            }
        }
        onNodeWithTag("row").performMouseInput {
            moveTo(center)
            press(MouseButton.Secondary)
            release(MouseButton.Secondary)
        }
        runOnIdle {
            assertEquals(0, activations)
            assertEquals(1, menus)
        }
        onNodeWithTag("row").performClick()
        runOnIdle { assertEquals(1, activations) }
    }

    @Test
    fun `touch density retains full targets for icons rows fields and interactive chips`() =
        runSkikoComposeUiTest(size = Size(420f, 420f)) {
            setContent {
                HbTheme(dimensions = HbDimensions()) {
                    Column {
                        HbIconButton(HbIcons.Home, "Home", {}, Modifier.testTag("icon"), size = 20.dp)
                        HbNavigationItem("Conversation", {}, Modifier.testTag("row"), minHeight = 28.dp)
                        HbTextField("", {}, Modifier.testTag("field"))
                        HbChip("Project", Modifier.testTag("chip"), onClick = {})
                    }
                }
            }
            listOf("icon", "row", "field", "chip").forEach { tag ->
                assertTrue(onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height >= 48f, "$tag touch target")
            }
        }
}
