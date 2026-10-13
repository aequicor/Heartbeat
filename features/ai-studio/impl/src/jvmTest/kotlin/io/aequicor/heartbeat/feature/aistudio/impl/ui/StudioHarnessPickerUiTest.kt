package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarness
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.EnvironmentUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.HarnessChoicesUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.harness_menu_section
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StudioHarnessPickerUiTest {
    @Test
    fun `switching to a project without harnesses removes the control and its open picker`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            var pane by mutableStateOf(PaneUi(0, projectId = "project"))
            var harnessLabel = ""
            setContent {
                harnessLabel = stringResource(Res.string.harness_menu_section)
                HbTheme { StudioComposer(state.paneContent(pane), {}, isCompact = false) }
            }
            onNodeWithContentDescription(harnessLabel).performClick()
            onNodeWithText("Разработка").assertIsDisplayed()
            runOnIdle { pane = pane.copy(projectId = "other") }
            onNodeWithTag("harness-picker-0").assertDoesNotExist()
            onNodeWithText("Разработка").assertDoesNotExist()
            onNodeWithText("This computer").assertDoesNotExist()
        }

    @Test
    fun `context wraps with reachable project harness and worktree controls at phone tablet and desktop widths`() {
        for (width in listOf(320f, 420f, 1024f, 1280f)) {
            for (dark in listOf(false, true)) renderComposer(width, dark)
        }
    }

    private fun renderComposer(width: Float, dark: Boolean) = runSkikoComposeUiTest(size = Size(width, 800f)) {
        var harnessLabel = ""
        setContent {
            harnessLabel = stringResource(Res.string.harness_menu_section)
            HbTheme(
                darkTheme = dark,
                dimensions = if (width == 1280f) HbDimensions.Desktop else HbDimensions.Mobile,
            ) {
                Box(
                    Modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).padding(HbTheme.spacing.xl),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    StudioComposer(state.paneContent(PaneUi(0, projectId = "project")), {}, isCompact = width < 720f)
                }
            }
        }
        onNodeWithTag("project-chip-0").assertIsDisplayed()
        onNodeWithTag("harness-picker-0").assertIsDisplayed()
        onNodeWithTag("worktree-mode-0").assertIsDisplayed()
        val project = onNodeWithTag("project-chip-0").fetchSemanticsNode().boundsInRoot
        val harness = onNodeWithTag("harness-picker-0").fetchSemanticsNode().boundsInRoot
        val worktree = onNodeWithTag("worktree-mode-0").fetchSemanticsNode().boundsInRoot
        val composer = onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot
        assertEquals(project.height, harness.height, "Project and harness controls must have equal height")
        assertEquals(project.height, worktree.height, "Project and worktree controls must have equal height")
        assertEquals(project.center.y, harness.center.y, 1f, "Identity controls must share a row")
        if (width >= 420f) {
            assertEquals(project.center.y, worktree.center.y, 1f, "Worktree must use available space in the row")
            assertTrue(worktree.left >= harness.right, "Context controls must not overlap")
        } else {
            assertTrue(worktree.top >= project.bottom, "Worktree must wrap when the row is full")
        }
        for (bounds in listOf(project, harness, worktree)) {
            assertTrue(bounds.left >= composer.left && bounds.right <= composer.right, "Context must fit the pane")
        }
        val directory = File("build/reports/harness-picker").apply { mkdirs() }
        check(ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "composer-$width-$dark.png")))
        onNodeWithContentDescription(harnessLabel).performClick()
        onNodeWithText("Разработка").assertIsDisplayed()
        check(ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "picker-$width-$dark.png")))
    }

    private val state = AiStudioScreenState(
        projects = persistentListOf(ProjectUi("project", "Heartbeat", EnvironmentUi.Local, "master")),
        isWorktreeAvailable = true,
        harnessChoices = HarnessChoicesUi(
            options = persistentListOf(StudioHarness("code", "Разработка", "Код и тесты", setOf("project"))),
        ),
    )
}
