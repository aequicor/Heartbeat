package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbMotion
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbNavigationUiTest {
    private val items = persistentListOf(
        HbMenuItem("rename", "Rename", HbIcons.Edit, shortcut = "Ctrl+R"),
        HbMenuItem("pin", "Pin", HbIcons.Pin, isEnabled = false),
        HbMenuItem("archive", "Archive", HbIcons.Archive, startsGroup = true),
    )

    @Test
    fun `icon button announces its label and selection and disabled buttons ignore clicks`() =
        runSkikoComposeUiTest(size = Size(320f, 120f)) {
            var clicks = 0
            setContent {
                HbTheme {
                    HbGlassScene {
                        HbIconButton(HbIcons.Home, "Home", onClick = { clicks++ }, isSelected = true)
                        HbIconButton(
                            HbIcons.Archive,
                            "Archive",
                            onClick = { clicks++ },
                            modifier = Modifier.padding(start = 48.dp),
                            enabled = false,
                        )
                    }
                }
            }
            onNodeWithContentDescription("Home")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
                .performClick()
            onNodeWithContentDescription("Archive").assertIsNotEnabled().performClick()
            runOnIdle { assertEquals(1, clicks) }
        }

    @Test
    fun `navigation rows select and informational chips expose no action`() =
        runSkikoComposeUiTest(size = Size(320f, 200f)) {
            var selected by mutableStateOf("")
            setContent {
                HbTheme {
                    HbGlassScene {
                        Column {
                            HbNavigationItem(
                                label = "First",
                                onClick = { selected = "first" },
                                isSelected = selected == "first",
                            )
                            HbNavigationItem("Second", onClick = { selected = "second" }, level = 1)
                            HbChip("main", icon = HbIcons.Branch)
                        }
                    }
                }
            }
            onNodeWithText("First").performClick()
            runOnIdle { assertEquals("first", selected) }
            onNodeWithText("Second").performClick()
            runOnIdle { assertEquals("second", selected) }
            onNodeWithContentDescription("main").assert(!hasClickAction())
        }

    @Test
    fun `menu button opens items, skips disabled ones with arrows and returns the chosen id`() =
        runSkikoComposeUiTest(size = Size(480f, 400f)) {
            var isExpanded by mutableStateOf(false)
            val chosen = mutableListOf<String>()
            setContent {
                HbTheme {
                    Box(Modifier.padding(16.dp).testTag("host")) {
                        HbMenuButton(
                            icon = HbIcons.More,
                            contentDescription = "More actions",
                            items = items,
                            isExpanded = isExpanded,
                            onExpandedChange = { isExpanded = it },
                            onItem = { chosen += it },
                        )
                    }
                }
            }
            onNodeWithContentDescription("More actions").performClick()
            runOnIdle { assertTrue(isExpanded) }
            onNodeWithText("Rename").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
            onNodeWithText("Archive").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            runOnIdle {
                assertEquals(listOf("archive"), chosen)
                assertFalse(isExpanded)
            }
            onNodeWithContentDescription("More actions").performClick()
            onNodeWithText("Rename").performKeyInput { pressKey(Key.Escape) }
            runOnIdle { assertFalse(isExpanded) }
            onNodeWithText("Rename").assertDoesNotExist()
        }

    @Test
    fun `activity indicator stays static under reduced motion`() = runSkikoComposeUiTest(size = Size(64f, 64f)) {
        setContent {
            HbTheme(motion = HbMotion(isReducedMotion = true)) {
                HbActivityIndicator(contentDescription = "Working")
            }
        }
        onNodeWithContentDescription("Working").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion),
        )
    }
}
