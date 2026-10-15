package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ContextUsageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EnvironmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.HarnessChoicesUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.HarnessOptionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioAppUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StudioComposerContextWidthUiTest {
    @Test
    fun `context width is independent of wrapping and keeps every control on one row`() = runSkikoComposeUiTest(
        size = Size(1200f, 700f),
    ) {
        val reports = mutableListOf<Int>()
        var width by mutableStateOf(900.dp)
        setContent {
            HbTheme(dimensions = HbDimensions.Desktop) {
                StudioComposer(content, {}, false, Modifier.width(width), onContextWidthChange = reports::add)
            }
        }
        waitForIdle()
        val required = reports.last()
        assertTrue(required > 0)
        width = 500.dp
        waitForIdle()
        assertEquals(required, reports.last(), "Wrapping must not change the measured one-line width")
        width = required.dp
        waitForIdle()
        val controls = listOf("project-chip-0", "harness-picker-0", "usage-0", "app-chip-0", "worktree-mode-0")
            .map { onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        controls.forEach { assertEquals(controls.first().center.y, it.center.y, 1f) }
        val composer = onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot
        assertTrue(controls.all { it.left >= composer.left && it.right <= composer.right })
        assertEquals(required, reports.last())
    }

    @Test
    fun `an open harness menu does not enter the context width measurement`() = runSkikoComposeUiTest(
        size = Size(1200f, 700f),
    ) {
        val reports = mutableListOf<Int>()
        var width by mutableStateOf(900.dp)
        setContent {
            HbTheme(dimensions = HbDimensions.Desktop) {
                StudioComposer(content, {}, false, Modifier.width(width), onContextWidthChange = reports::add)
            }
        }
        waitForIdle()
        val required = reports.last()
        onNodeWithTag("harness-picker-0").performClick()
        onNodeWithText("Other harness").assertIsDisplayed()
        width = 600.dp
        waitForIdle()
        onNodeWithText("Other harness").assertIsDisplayed()
        assertEquals(required, reports.last())
    }

    @Test
    fun `removing all context controls clears the required width`() = runSkikoComposeUiTest {
        val reports = mutableListOf<Int>()
        var selected by mutableStateOf(content)
        setContent {
            HbTheme(dimensions = HbDimensions.Desktop) {
                StudioComposer(selected, {}, false, onContextWidthChange = reports::add)
            }
        }
        waitForIdle()
        assertTrue(reports.last() > 0)
        val pane = PaneUi(0, sessionId = "chat")
        selected = AiStudioScreenState(panes = persistentListOf(pane), models = StudioModelOptions).paneContent(pane)
        waitForIdle()
        assertEquals(0, reports.last())
    }

    private val content: PaneContent = run {
        val pane = PaneUi(0, projectId = "project")
        AiStudioScreenState(
            panes = persistentListOf(pane),
            models = StudioModelOptions,
            projects = persistentListOf(
                ProjectUi("project", "Training and weekly progress", EnvironmentUi.Local, "master"),
            ),
            isWorktreeAvailable = true,
            harnessChoices = HarnessChoicesUi(
                options = persistentListOf(
                    HarnessOptionUi("gym", "Detailed training program", projectIds = persistentSetOf("project")),
                    HarnessOptionUi("other", "Other harness", projectIds = persistentSetOf("project")),
                ),
                panes = persistentMapOf(0 to persistentSetOf("gym")),
            ),
            apps = persistentMapOf(
                0 to StudioAppUi("app", "Training journal", "gym", null, "project", persistentSetOf("gym")),
            ),
        ).paneContent(pane).copy(contextUsage = ContextUsageUi(100, 1000, 10))
    }
}
