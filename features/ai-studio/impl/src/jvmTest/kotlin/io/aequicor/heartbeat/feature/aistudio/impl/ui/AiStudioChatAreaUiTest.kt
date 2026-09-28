package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.reduce
import kotlin.test.Test

/** Research replaces only the chat area: the studio sidebar, its toggle and the header stay with the studio. */
@OptIn(ExperimentalTestApi::class)
class AiStudioChatAreaUiTest {
    private object FakeResearch : ComposableComponent {
        @Composable
        override fun Content(modifier: Modifier) {
            Box(modifier.testTag("fake-research"))
        }
    }

    @Test
    fun `research fills the chat area while the sidebar and its toggle remain`() =
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            var state by mutableStateOf(desktopAuditWorkspace(isEmpty = true))
            setContent {
                HbTheme(darkTheme = false) {
                    AiStudioContent(state, { intent ->
                        if (intent is AiStudioScreenIntent.Sidebar) {
                            state = state.copy(sidebar = state.sidebar.reduce(intent))
                        }
                    }, StudioExits(onBack = {}, onOpenToggles = {}), chatArea = FakeResearch)
                }
            }
            onNodeWithTag("studio-sidebar").assertIsDisplayed()
            onNodeWithTag("fake-research").assertIsDisplayed()
            onNodeWithTag("composer-0").assertDoesNotExist()
            // The open sidebar owns its collapse button, so the chat area header offers none.
            onNodeWithTag("chat-area-open-sidebar").assertDoesNotExist()
            onNodeWithTag("rail-sidebar").performClick()
            mainClock.advanceTimeBy(1000)
            onNodeWithTag("studio-sidebar").assertDoesNotExist()
            onNodeWithTag("fake-research").assertIsDisplayed()
            onNodeWithTag("chat-area-open-sidebar").performClick()
            mainClock.advanceTimeBy(1000)
            onNodeWithTag("studio-sidebar").assertIsDisplayed()
        }
}
