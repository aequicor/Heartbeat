package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The composer's popups share the menu host: on desktop an outside click dismisses a focusable popup and never
 * activates the content beneath (which may be an embedded native surface that Compose never sees). Suggestions
 * are not focusable and let outside input reach the editor beneath.
 */
@OptIn(ExperimentalTestApi::class)
class HbComposerPopupsUiTest {
    @Test
    fun `outside clicks dismiss the composer menu without reaching the content beneath`() {
        assertOutsideClicks { onDismiss ->
            ComposerMenuPopup(persistentListOf(HbComposerAction("attach", "Attach")), "Actions", onDismiss, {})
        }
    }

    @Test
    fun `outside clicks dismiss the usage popup without reaching the content beneath`() {
        assertOutsideClicks { onDismiss -> ComposerUsagePopup("Usage", onDismiss) { HbText("Context") } }
    }

    @Test
    fun `outside clicks on suggestions reach the editor beneath`() = runSkikoComposeUiTest(size = Size(WIDTH, HEIGHT)) {
        val host = PopupHost()
        setPopup(host) { onDismiss ->
            val suggestions = HbComposerSuggestions(persistentListOf(HbComposerSuggestion("help", "/help")))
            ComposerSuggestionsPopup(suggestions, "Suggestions", onDismiss, {})
        }
        CORNERS.forEach { point ->
            host.isOpen = true
            waitForIdle()
            onNodeWithText("/help").assertExists()
            // The suggestions layer has no backdrop: the window content takes the click directly.
            onAllNodes(isRoot()).onFirst().performMouseInput {
                moveTo(point)
                press()
                release()
            }
        }
        runOnIdle { assertEquals(CORNERS.size, host.underlyingClicks) }
    }

    @Test
    fun `a click inside the composer menu activates its item`() = runSkikoComposeUiTest(size = Size(WIDTH, HEIGHT)) {
        val host = PopupHost()
        var actions = emptyList<String>()
        setPopup(host) { onDismiss ->
            ComposerMenuPopup(
                persistentListOf(HbComposerAction("attach", "Attach")),
                "Actions",
                onDismiss,
                onAction = { actions = actions + it },
            )
        }
        onNodeWithText("Attach").performMouseInput {
            moveTo(center)
            press()
            release()
        }
        runOnIdle {
            assertEquals(listOf("attach"), actions)
            assertEquals(0, host.underlyingClicks)
        }
    }

    private fun assertOutsideClicks(popup: @Composable (onDismiss: () -> Unit) -> Unit) =
        runSkikoComposeUiTest(size = Size(WIDTH, HEIGHT)) {
            val host = PopupHost()
            setPopup(host, popup)
            CORNERS.forEach { point ->
                host.isOpen = true
                waitForIdle()
                // The popup layer is the last root: outside its sheet it must cover the whole window.
                onAllNodes(isRoot()).onLast().performMouseInput {
                    moveTo(point)
                    press()
                    release()
                }
                runOnIdle { assertEquals(false, host.isOpen) }
            }
            runOnIdle {
                assertEquals(4, host.dismissed)
                assertEquals(0, host.underlyingClicks)
            }
        }

    private fun SkikoComposeUiTest.setPopup(host: PopupHost, popup: @Composable (onDismiss: () -> Unit) -> Unit) =
        setContent {
            HbTheme {
                Box(Modifier.fillMaxSize().clickable { host.underlyingClicks++ }) {
                    Box(Modifier.offset(x = 300.dp, y = 500.dp).size(32.dp)) {
                        if (host.isOpen) {
                            popup {
                                host.dismissed++
                                host.isOpen = false
                            }
                        }
                    }
                }
            }
        }

    private class PopupHost {
        var isOpen by mutableStateOf(true)
        var dismissed = 0
        var underlyingClicks = 0
    }

    private companion object {
        const val WIDTH = 800f
        const val HEIGHT = 600f
        val CORNERS = listOf(
            Offset(1f, 1f),
            Offset(WIDTH - 1f, 1f),
            Offset(1f, HEIGHT - 1f),
            Offset(WIDTH - 1f, HEIGHT - 1f),
        )
    }
}
