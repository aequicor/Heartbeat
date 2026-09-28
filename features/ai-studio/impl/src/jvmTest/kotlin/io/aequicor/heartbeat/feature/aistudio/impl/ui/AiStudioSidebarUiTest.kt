package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.test.withKeyDown
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.reduce
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.action_unpin
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Desktop accelerators and pointer menus use the same existing intents as their visible controls. */
@OptIn(ExperimentalTestApi::class)
class AiStudioSidebarUiTest {
    @Test
    fun `shortcuts from editor open search hide sidebar and create one chat`() =
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            var state by mutableStateOf(desktopAuditWorkspace(isEmpty = true))
            val events = mutableListOf<AiStudioScreenIntent>()
            setContent {
                HbTheme(darkTheme = false) {
                    AiStudioContent(state, { intent ->
                        events += intent
                        if (intent is AiStudioScreenIntent.Sidebar) {
                            state = state.copy(sidebar = state.sidebar.reduce(intent))
                        }
                    }, sidebarExits)
                }
            }
            val editor = onNode(hasAnyAncestor(hasTestTag("composer-0")) and hasSetTextAction())
            editor.performClick().performKeyInput {
                withKeyDown(if (isStudioMetaShortcut()) Key.CtrlLeft else Key.MetaLeft) { pressKey(Key.N) }
            }
            assertTrue(events.filterIsInstance<AiStudioScreenIntent.NewSession>().isEmpty())
            editor.shortcut(Key.K)
            onNodeWithTag("sidebar-search").assertIsFocused().shortcut(Key.K)
            assertTrue(state.sidebar.isSearchVisible, "Repeated search shortcut must retain the open search")
            assertEquals(1, events.count { it == AiStudioScreenIntent.ToggleSearch })
            onNodeWithTag("sidebar-search").performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag("sidebar-search-open").assertIsDisplayed()

            editor.performClick().shortcut(Key.Backslash)
            mainClock.advanceTimeBy(1000)
            onNodeWithTag("studio-sidebar").assertDoesNotExist()
            editor.shortcut(Key.K)
            mainClock.advanceTimeBy(1000)
            onNodeWithTag("sidebar-search").assertIsFocused()
            onNodeWithTag("sidebar-search").shortcut(Key.N)
            assertEquals(1, events.filterIsInstance<AiStudioScreenIntent.NewSession>().size)
            onNodeWithTag("sidebar-search").shortcut(Key.Backslash)
            mainClock.advanceTimeBy(1000)
            onNodeWithTag("studio-workspace").shortcut(Key.K)
            mainClock.advanceTimeBy(1000)
            onNodeWithTag("sidebar-search").assertIsFocused()
        }

    @Test
    fun `compact search shortcut reveals drawer and sidebar shortcut closes it`() =
        runSkikoComposeUiTest(size = Size(390f, 844f)) {
            var state by mutableStateOf(desktopAuditWorkspace(isEmpty = true))
            setContent {
                HbTheme(darkTheme = false) {
                    AiStudioContent(state, { intent ->
                        if (intent is AiStudioScreenIntent.Sidebar) {
                            state = state.copy(sidebar = state.sidebar.reduce(intent))
                        }
                    }, sidebarExits)
                }
            }
            onNodeWithTag("studio-workspace").shortcut(Key.K)
            onNodeWithTag("studio-drawer").assertIsDisplayed()
            onNodeWithTag("sidebar-search").assertIsFocused().shortcut(Key.Backslash)
            onNodeWithTag("studio-drawer").assertDoesNotExist()
            onNodeWithTag("studio-workspace").shortcut(Key.K)
            onNodeWithTag("sidebar-search").assertIsFocused()
        }

    @Test
    fun `secondary click opens existing session menu without opening the chat`() =
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            var unpin = ""
            setContent {
                unpin = stringResource(Res.string.action_unpin)
                HbTheme(darkTheme = false) {
                    AiStudioContent(desktopAuditWorkspace(isEmpty = true), events::add, sidebarExits)
                }
            }
            onNodeWithTag("session-audit-0").performMouseInput {
                moveTo(center)
                press(MouseButton.Secondary)
                release(MouseButton.Secondary)
            }
            onNodeWithText(unpin).assertIsDisplayed().performKeyInput { pressKey(Key.Escape) }
            onNodeWithText(unpin).assertDoesNotExist()
            onNodeWithTag("session-audit-0").performMouseInput {
                press(MouseButton.Secondary)
                release(MouseButton.Secondary)
            }
            onNodeWithText(unpin).assertIsDisplayed().performClick()
            assertEquals(listOf<AiStudioScreenIntent>(AiStudioScreenIntent.SetPinned("audit-0", false)), events)
        }
}

@OptIn(ExperimentalTestApi::class)
private fun SemanticsNodeInteraction.shortcut(key: Key) = performKeyInput {
    withKeyDown(if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft) {
        pressKey(key)
    }
}

private val sidebarExits = StudioExits(onBack = {}, onOpenToggles = {})
