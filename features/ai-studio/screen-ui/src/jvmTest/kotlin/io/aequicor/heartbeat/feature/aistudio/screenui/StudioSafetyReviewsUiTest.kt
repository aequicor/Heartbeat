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
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.safety_reviews_abandoned
import io.aequicor.heartbeat.feature.aistudio.impl.resources.safety_reviews_busy
import io.aequicor.heartbeat.feature.aistudio.impl.resources.safety_reviews_finishing
import io.aequicor.heartbeat.feature.aistudio.impl.resources.safety_reviews_label
import io.aequicor.heartbeat.feature.aistudio.impl.resources.safety_reviews_label_queued
import io.aequicor.heartbeat.feature.aistudio.impl.resources.safety_reviews_queued
import io.aequicor.heartbeat.feature.aistudio.impl.resources.safety_reviews_title
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SafetyReviewsUi
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The sidebar header shows how many automatic safety reviews run and wait. */
@OptIn(ExperimentalTestApi::class)
class StudioSafetyReviewsUiTest {
    @Test
    fun `the sidebar header shows busy slots, the queue and details`() = runSkikoComposeUiTest(size = WINDOW) {
        val reviews = SafetyReviewsUi(capacity = 3, running = 2, queued = 4, finishing = 1, abandoned = 5)
        render(desktopAuditWorkspace(isEmpty = true).copy(safetyReviews = reviews))

        val expected = runBlocking {
            val label = getString(Res.string.safety_reviews_label_queued, 3, 3, 4)
            assertEquals("3/3 · +4", label)
            listOf(
                getString(Res.string.safety_reviews_title),
                getString(Res.string.safety_reviews_busy, 3, 3),
                getString(Res.string.safety_reviews_queued, 4),
                getString(Res.string.safety_reviews_finishing, 1),
                getString(Res.string.safety_reviews_abandoned, 5),
            ).joinToString("\n")
        }
        assertEquals(expected, description())
    }

    @Test
    fun `without a queue only the taken slots are shown and announced once`() = runSkikoComposeUiTest(size = WINDOW) {
        render(desktopAuditWorkspace(isEmpty = true).copy(safetyReviews = SafetyReviewsUi(capacity = 3, running = 2)))

        val expected = runBlocking {
            val label = getString(Res.string.safety_reviews_label, 2, 3)
            assertEquals("2/3", label)
            listOf(
                getString(Res.string.safety_reviews_title),
                getString(Res.string.safety_reviews_busy, 2, 3),
            ).joinToString("\n")
        }
        assertEquals(expected, description())
        val config = onNodeWithTag("sidebar-safety-reviews").fetchSemanticsNode().config
        assertFalse(SemanticsProperties.Text in config)
    }

    @Test
    fun `an idle reviewer shows nothing`() = runSkikoComposeUiTest(size = WINDOW) {
        render(desktopAuditWorkspace(isEmpty = true))

        assertEquals(0, onAllNodesWithTag("sidebar-safety-reviews").fetchSemanticsNodes().size)
    }

    private fun SkikoComposeUiTest.description(): String = onNodeWithTag("sidebar-safety-reviews")
        .assertIsDisplayed()
        .fetchSemanticsNode()
        .config[SemanticsProperties.ContentDescription]
        .single()

    private fun SkikoComposeUiTest.render(state: AiStudioScreenState) {
        setContent {
            HbTheme(darkTheme = false) {
                AiStudioContent(state, {}, StudioExits(onBack = {}, onOpenToggles = {}))
            }
        }
    }
}

private val WINDOW = Size(1280f, 800f)
