package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SafetyReviewsUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The sidebar header shows how many automatic safety reviews run and wait. */
@OptIn(ExperimentalTestApi::class)
class StudioSafetyReviewsUiTest {
    @Test
    fun `the sidebar header shows busy slots, the queue and details`() = runSkikoComposeUiTest(size = WINDOW) {
        val reviews = SafetyReviewsUi(capacity = 3, running = 2, queued = 4, finishing = 1, abandoned = 1)
        render(desktopAuditWorkspace(isEmpty = true).copy(safetyReviews = reviews))

        val node = onNodeWithTag("sidebar-safety-reviews", useUnmergedTree = false).assertIsDisplayed()
        val semantics = node.fetchSemanticsNode().config
        val description = semantics[SemanticsProperties.ContentDescription].single()
        // Title, running, queued, finishing and abandoned lines, in the current locale.
        val lines = description.lines()
        assertEquals(5, lines.size, description)
        assertTrue(lines[1].contains("2") && lines[1].contains("3"), description)
        assertTrue(lines[2].endsWith("4"), description)
        assertTrue(lines[3].endsWith("1") && lines[4].endsWith("1"), description)
        val label = semantics[SemanticsProperties.Text].joinToString { it.text }
        assertEquals("3/3 · +4", label)
    }

    @Test
    fun `an idle reviewer shows nothing`() = runSkikoComposeUiTest(size = WINDOW) {
        render(desktopAuditWorkspace(isEmpty = true))

        assertEquals(0, onAllNodesWithTag("sidebar-safety-reviews").fetchSemanticsNodes().size)
    }

    private fun SkikoComposeUiTest.render(state: AiStudioScreenState) {
        setContent {
            HbTheme(darkTheme = false) {
                AiStudioContent(state, {}, StudioExits(onBack = {}, onOpenToggles = {}))
            }
        }
    }
}

private val WINDOW = Size(1280f, 800f)
