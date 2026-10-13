package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserPhase
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenIntent
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenState
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserTabUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BrowserGroupMenuTest {
    @Test
    fun `switching groups restores their last live tab and inventory can select another group`() =
        runSkikoComposeUiTest {
            var state by mutableStateOf(groupedState())
            setContent {
                HbTheme(dimensions = HbDimensions.Desktop) {
                    BrowserScreenContent(state, {
                        if (it is BrowserScreenIntent.SelectTab) state = state.copy(selectedTabId = it.id)
                    })
                }
            }
            onNodeWithTag("browser-tab-a1").assertIsDisplayed()
            onNodeWithTag("browser-tab-b1").assertDoesNotExist()
            onNodeWithTag("browser-tab-a2").performClick()
            onNodeWithTag("browser-all-tabs").performClick()
            onAllNodes(hasText("3") and hasClickAction()).onLast().performClick()
            runOnIdle { assertEquals("b1", state.selectedTabId) }
            onNodeWithTag("browser-tab-a1").assertDoesNotExist()
            onNodeWithTag("browser-tab-b2").performClick()
            onNodeWithTag("browser-all-tabs").performClick()
            onAllNodes(hasText("2") and hasClickAction()).onLast().performClick()
            runOnIdle { assertEquals("a2", state.selectedTabId) }
            onNodeWithTag("browser-all-tabs").performClick()
            onAllNodes(hasText("3") and hasClickAction()).onLast().performClick()
            runOnIdle { assertEquals("b2", state.selectedTabId) }

            onNodeWithTag("browser-all-tabs").performClick()
            onAllNodesWithText("Personal page").onLast().performScrollTo().performClick()
            runOnIdle { assertEquals("personal", state.selectedTabId) }
            onNodeWithTag("browser-tab-personal").assertIsDisplayed()
            onNodeWithTag("browser-tab-b2").assertDoesNotExist()

            // A previously visited tab closed in the background must never be selected again.
            runOnIdle { state = state.copy(tabs = state.tabs.filter { it.id != "a2" }.toImmutableList()) }
            onNodeWithTag("browser-all-tabs").performClick()
            // First enabled entry is group A, now containing one surviving tab.
            onAllNodes(
                hasClickAction() and hasText("1"),
            ).onLast().performKeyInput {
                pressKey(Key.MoveHome)
                pressKey(Key.Enter)
            }
            runOnIdle { assertEquals("a1", state.selectedTabId) }
            // External selection (including restoration) switches the visible group immediately.
            runOnIdle { state = state.copy(selectedTabId = "b3") }
            onNodeWithTag("browser-tab-b3").assertIsDisplayed()
            onNodeWithTag("browser-tab-a1").assertDoesNotExist()
        }

    @Test
    fun `keyboard switches groups and Escape restores trigger focus`() = runSkikoComposeUiTest {
        var state by mutableStateOf(groupedState())
        setContent {
            HbTheme(dimensions = HbDimensions.Desktop) {
                BrowserScreenContent(state, {
                    if (it is BrowserScreenIntent.SelectTab) state = state.copy(selectedTabId = it.id)
                })
            }
        }
        onNodeWithTag("browser-all-tabs").performClick()
        onAllNodes(
            hasText("2") and hasClickAction(),
        ).onLast().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        runOnIdle { assertEquals("b1", state.selectedTabId) }
        onAllNodes(
            hasText("3") and hasClickAction(),
        ).onLast().assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        onAllNodes(hasText("2") and hasClickAction()).onLast().performKeyInput { pressKey(Key.Escape) }
        onAllNodes(hasText("3") and hasClickAction()).onLast().assertIsFocused()
    }

    @Test
    fun `group menu fits compact and desktop panes in both themes`() {
        for (width in listOf(320, 420, 1280)) {
            for (dark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width.toFloat(), 800f)) {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f)) {
                            HbTheme(
                                darkTheme = dark,
                                dimensions = if (width == 320) HbDimensions.Mobile else HbDimensions.Desktop,
                            ) {
                                BrowserScreenContent(groupedState(), {})
                            }
                        }
                    }
                    onNodeWithTag("browser-new-tab").assertIsDisplayed()
                    onNodeWithTag("browser-all-tabs").assertIsDisplayed().performClick()
                    onAllNodes(hasText("3") and hasClickAction()).onLast().assertIsDisplayed()
                    val directory = File("build/reports/browser").apply { mkdirs() }
                    ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "groups-$width-$dark.png"))
                }
            }
        }
    }

    private fun groupedState() = BrowserScreenState(
        phase = BrowserPhase.Ready,
        hasTabs = true,
        selectedTabId = "a1",
        tabs = persistentListOf(
            BrowserTabUi("a1", "First page", false, true, groupId = "a"),
            BrowserTabUi("a2", "Second page", false, true, groupId = "a"),
            BrowserTabUi("b1", "Other chat", false, true, groupId = "b"),
            BrowserTabUi("b2", "Other source", false, true, groupId = "b"),
            BrowserTabUi("b3", "Third source", false, true, groupId = "b"),
            BrowserTabUi("personal", "Personal page", true, false),
        ),
    )
}
