package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
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
        HbMenuItem("archive", "Archive", HbIcons.Archive, isGroupStart = true),
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
    fun `conversation preview selects its row and nested actions stay reachable by keyboard`() =
        runSkikoComposeUiTest(size = Size(320f, 120f)) {
            var actions = 0
            var isSelected by mutableStateOf(false)
            setContent {
                HbTheme {
                    HbGlassScene {
                        HbNavigationItem(
                            "Session",
                            onClick = { isSelected = true },
                            isSelected = isSelected,
                            supportingText = "Latest message",
                            leadingContent = { HbIcon(HbIcons.Chat, contentDescription = null) },
                            selectedBackground = HbTheme.studioColors.selected,
                            selectedForeground = HbTheme.studioColors.onSelected,
                        ) { isActive ->
                            if (isActive) HbIconButton(HbIcons.More, "Session actions", onClick = { actions++ })
                        }
                    }
                }
            }
            onNodeWithText("Latest message").performClick()
            onNodeWithText("Session").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            onNodeWithText("Session").requestFocus()
            onNodeWithContentDescription("Session actions").assertExists()
            onNodeWithText("Session").performKeyInput { pressKey(Key.Tab) }
            onNodeWithContentDescription("Session actions").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            runOnIdle { assertEquals(1, actions) }
        }

    @Test
    fun `desktop rows reveal actions for mouse hover or real touch without activating unrelated rows`() =
        runSkikoComposeUiTest(size = Size(320f, 200f)) {
            var rowClicks = 0
            var actionClicks = 0
            setContent {
                HbTheme {
                    Column {
                        listOf("First", "Second").forEach { label ->
                            HbNavigationItem(label, { rowClicks++ }, Modifier.testTag(label)) { isActive ->
                                if (isActive) {
                                    HbIconButton(HbIcons.More, "$label actions", { actionClicks++ })
                                }
                            }
                        }
                        HbButton("Outside", {})
                    }
                }
            }
            val first = onNodeWithTag("First")
            val firstActions = onNodeWithContentDescription("First actions")
            firstActions.assertDoesNotExist()
            onNodeWithContentDescription("Second actions").assertDoesNotExist()
            first.performMouseInput { moveTo(center) }
            firstActions.assertExists()
            first.performMouseInput { exit() }
            firstActions.assertDoesNotExist()

            first.performTouchInput { click() }
            onNodeWithText("Outside").requestFocus()
            firstActions.assertExists().performTouchInput { click() }
            onNodeWithContentDescription("Second actions").assertDoesNotExist()
            onNodeWithText("Outside").requestFocus()
            first.performMouseInput {
                moveTo(center)
                exit()
            }
            firstActions.assertDoesNotExist()
            runOnIdle {
                assertEquals(1, rowClicks, "Pointer observation must preserve the ordinary row click")
                assertEquals(1, actionClicks, "The nested action must not also click its row")
            }
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
            onNodeWithContentDescription("More actions").assertIsFocused()
        }

    @Test
    fun `menu command can leave focus in the editor it opens`() = runSkikoComposeUiTest(size = Size(480f, 400f)) {
        var expanded by mutableStateOf(false)
        var editing by mutableStateOf(false)
        var title by mutableStateOf("")
        var commits = 0
        setContent {
            HbTheme {
                Column {
                    if (editing) {
                        val focus = remember { FocusRequester() }
                        var hadFocus by remember { mutableStateOf(false) }
                        SideEffect(focus) { focus.requestFocus() }
                        HbTextField(
                            title,
                            { title = it },
                            Modifier.testTag("editor").focusRequester(focus).onFocusChanged {
                                if (it.isFocused) {
                                    hadFocus = true
                                } else if (hadFocus) {
                                    commits++
                                    editing = false
                                }
                            },
                        )
                    }
                    HbMenuButton(
                        icon = HbIcons.More,
                        contentDescription = "Actions",
                        items = persistentListOf(HbMenuItem("rename", "Rename", isFocusRestoredOnSelect = false)),
                        isExpanded = expanded,
                        onExpandedChange = { expanded = it },
                        onItem = { editing = true },
                    )
                }
            }
        }
        onNodeWithContentDescription("Actions").performClick()
        onNodeWithText("Rename").performClick()
        onNodeWithTag("editor").assertIsFocused().performTextInput("Renamed")
        runOnIdle {
            assertEquals("Renamed", title)
            assertEquals(0, commits)
        }
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
