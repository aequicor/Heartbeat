package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ApprovalUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ModelUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanAuthorUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanImplementationModeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanProposalUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanStatusUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanUi
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

@OptIn(ExperimentalTestApi::class)
class StudioPlanUiTest {
    private val plan = PlanUi(3, isPlanning = true, PlanStatusUi.Proposed, "Feature", "# Feature", PlanAuthorUi.Agent)

    @Test
    fun `the card of the current revision offers its decisions`() = runSkikoComposeUiTest(size = Size(600f, 600f)) {
        val approved = mutableListOf<Long>()
        var dialog: PlanDialog? = null
        setContent {
            HbTheme(darkTheme = false) {
                StudioPlanCard(PlanProposalUi(3, "Three steps"), plan, { approved += it }, {}, { dialog = it })
            }
        }
        onNodeWithText("Three steps").assertIsDisplayed()
        onNodeWithTag("plan-approve").performClick()
        onNodeWithTag("plan-implement").performClick()
        assertEquals(listOf(3L), approved)
        assertEquals(PlanDialog.Implement, dialog)
    }

    @Test
    fun `an older proposal only opens the plan`() = runSkikoComposeUiTest(size = Size(600f, 600f)) {
        setContent {
            HbTheme(darkTheme = false) {
                StudioPlanCard(PlanProposalUi(2, ""), plan, {}, {}, {})
            }
        }
        onNodeWithTag("plan-replaced").assertIsDisplayed()
        onNodeWithTag("plan-open").assertIsDisplayed()
        onNodeWithTag("plan-approve").assertDoesNotExist()
    }

    @Test
    fun `the editor saves from the revision it opened and offers a reload after a change`() =
        runSkikoComposeUiTest(size = Size(900f, 900f)) {
            var shown by mutableStateOf(plan)
            val saved = mutableListOf<Pair<String, Long>>()
            setContent {
                HbTheme(darkTheme = false) {
                    StudioPlanEditor(shown, { text, base -> saved += text to base }, {})
                }
            }
            onNodeWithTag("plan-editor").assertIsDisplayed()
            shown = plan.copy(revision = 4, text = "# Agent")
            waitForIdle()
            onNodeWithTag("plan-save").performClick()
            assertEquals("# Feature" to 4L, saved.single())
        }

    @Test
    fun `the implementation dialog sends the chosen route and mode`() = runSkikoComposeUiTest(
        size = Size(900f, 1200f),
    ) {
        val sent = mutableListOf<StudioIntent.Plan>()
        val models = persistentListOf(ModelUi("a", "Alpha"), ModelUi("b", "Beta"))
        val modes = persistentListOf(PlanImplementationModeUi.Chat, PlanImplementationModeUi.Organism)
        setContent {
            HbTheme(darkTheme = false) {
                StudioPlanImplementDialog(
                    plan,
                    PlanImplementationOffer(models, "a", ApprovalUi.Ask, modes),
                    onImplement = { sent += it },
                    onDismiss = {},
                    sessionId = "chat",
                )
            }
        }
        onNodeWithTag("plan-model-b").performClick()
        onNodeWithTag("plan-mode-Organism").performClick()
        onNodeWithTag("plan-implement-confirm").performClick()
        val intent = sent.single() as StudioIntent.ImplementPlan
        assertEquals("b", intent.modelId)
        assertEquals(PlanImplementationModeUi.Organism, intent.mode)
        assertEquals(3L, intent.revision)
        assertTrue(intent.prompt.isNotBlank())
    }
}
