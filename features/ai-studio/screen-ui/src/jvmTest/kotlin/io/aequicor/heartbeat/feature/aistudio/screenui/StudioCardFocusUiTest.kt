package io.aequicor.heartbeat.feature.aistudio.screenui

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
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.CardFocusRequestUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.studioCardKey
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StudioCardFocusUiTest {
    @Test
    fun `focus mounts an old reply and reveals its second card beyond a tall first card`() = runSkikoComposeUiTest(
        size = Size(1280f, 800f),
    ) {
        val initial = desktopAuditWorkspace(isEmpty = false)
        val messages = initial.transcripts.getValue("audit-0")
        val first = messages.filterIsInstance<MessageUi.Reply>().first()
        val reply = first.copy(cardIds = persistentListOf("first", "second"))
        val transcript = messages.map { if (it.id == first.id) reply else it }.toImmutableList()
        var state by mutableStateOf(
            initial.copy(
                transcripts = persistentMapOf("audit-0" to transcript),
            ),
        )
        val cards = persistentMapOf(
            studioCardKey(0, "first") to Card("first", "First card\n".repeat(60)),
            studioCardKey(0, "second") to Card("second", "Exact target card"),
        )
        var isAcknowledged = false
        setContent {
            HbTheme(darkTheme = true) {
                AiStudioContent(
                    state,
                    { intent ->
                        if (intent is AiStudioScreenIntent.CardFocusApplied) {
                            isAcknowledged = true
                            state = state.copy(cardFocusRequests = persistentMapOf())
                        }
                    },
                    StudioExits(onBack = {}, onOpenToggles = {}, cards = cards),
                )
            }
        }
        runOnIdle {
            state = state.copy(
                cardFocusRequests = persistentMapOf(0 to CardFocusRequestUi("audit-0", reply.id, "second", 1)),
            )
        }
        onNodeWithTag("second").assertIsDisplayed()
        runOnIdle { assertTrue(isAcknowledged) }
    }

    private class Card(private val id: String, private val text: String) : StudioEmbed {
        @Composable
        override fun Content(modifier: Modifier) {
            HbText(text, modifier.testTag(id))
        }
    }
}
