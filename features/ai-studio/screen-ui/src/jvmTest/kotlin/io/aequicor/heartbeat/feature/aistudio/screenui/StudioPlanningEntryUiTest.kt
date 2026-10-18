package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_add
import io.aequicor.heartbeat.feature.aistudio.impl.resources.planning_mode
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanAuthorUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanStatusUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

@OptIn(ExperimentalTestApi::class)
class StudioPlanningEntryUiTest {
    private val chat = SessionUi("chat", "Chat", projectId = null, updatedAt = Instant.DISTANT_PAST)
    private val plan = PlanUi(2, isPlanning = true, PlanStatusUi.Draft, "Plan", "# Plan", PlanAuthorUi.Agent)

    @Test
    fun `the plus menu of a new chat switches it to planning`() = runSkikoComposeUiTest(size = Size(900f, 700f)) {
        val events = mutableListOf<StudioIntent>()
        val pane = PaneUi(0)
        val state = AiStudioScreenState(
            panes = persistentListOf(pane),
            models = StudioModelOptions,
            isPlanningEnabled = true,
        )
        val labels = render(state, pane, events)
        onNodeWithContentDescription(labels.first).performClick()
        onNodeWithText(labels.second).performClick()
        runOnIdle { assertEquals(listOf<StudioIntent>(StudioIntent.SelectPlanning(0, true)), events) }
    }

    @Test
    fun `the plus menu of an idle chat starts planning in it`() = runSkikoComposeUiTest(size = Size(900f, 700f)) {
        val events = mutableListOf<StudioIntent>()
        val pane = PaneUi(0, sessionId = "chat")
        val state = AiStudioScreenState(
            panes = persistentListOf(pane),
            sessions = persistentListOf(chat),
            models = StudioModelOptions,
            isPlanningEnabled = true,
        )
        val labels = render(state, pane, events)
        onNodeWithContentDescription(labels.first).performClick()
        onNodeWithText(labels.second).performClick()
        runOnIdle { assertEquals(listOf<StudioIntent>(StudioIntent.SetPlanning("chat", true)), events) }
    }

    @Test
    fun `the plan chip of a planning chat leaves planning`() = runSkikoComposeUiTest(size = Size(900f, 700f)) {
        val events = mutableListOf<StudioIntent>()
        val pane = PaneUi(0, sessionId = "chat")
        val state = AiStudioScreenState(
            panes = persistentListOf(pane),
            sessions = persistentListOf(chat),
            models = StudioModelOptions,
            isPlanningEnabled = true,
            plans = persistentMapOf("chat" to plan),
        )
        render(state, pane, events)
        onNodeWithTag("planning-mode-0").performClick()
        runOnIdle { assertEquals(listOf<StudioIntent>(StudioIntent.SetPlanning("chat", false)), events) }
    }

    @Test
    fun `planning is not offered while the feature is off`() = runSkikoComposeUiTest(size = Size(900f, 700f)) {
        val pane = PaneUi(0)
        val labels = render(
            AiStudioScreenState(panes = persistentListOf(pane), models = StudioModelOptions),
            pane,
            mutableListOf(),
        )
        onNodeWithContentDescription(labels.first).performClick()
        onAllNodesWithText(labels.second).assertCountEquals(0)
    }

    private fun SkikoComposeUiTest.render(
        state: AiStudioScreenState,
        pane: PaneUi,
        events: MutableList<StudioIntent>,
    ): Pair<String, String> {
        var add = ""
        var mode = ""
        setContent {
            add = stringResource(Res.string.composer_add)
            mode = stringResource(Res.string.planning_mode)
            HbTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    StudioComposer(state.paneContent(pane), { events += it }, isCompact = false)
                }
            }
        }
        waitForIdle()
        return add to mode
    }
}
