package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ApprovalUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ModelUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanAuthorUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanImplementationModeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanProposalUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanStatusUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SecurityLevelUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ToolStatusUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ToolUi
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
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
    fun `a proposal without a revision only opens the plan`() = runSkikoComposeUiTest(size = Size(600f, 600f)) {
        setContent {
            HbTheme(darkTheme = false) {
                StudioPlanCard(PlanProposalUi(null, ""), plan, {}, {}, {})
            }
        }
        onNodeWithTag("plan-open").assertIsDisplayed()
        onNodeWithTag("plan-approve").assertDoesNotExist()
        onNodeWithTag("plan-implement").assertDoesNotExist()
        onNodeWithTag("plan-replaced").assertDoesNotExist()
        onNodeWithText("0", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a repeated proposal of the revision only opens the plan`() = runSkikoComposeUiTest(size = Size(600f, 600f)) {
        setContent {
            HbTheme(darkTheme = false) {
                StudioPlanCard(PlanProposalUi(3, ""), plan, {}, {}, {}, decisions = PlanCardDecisions(isLatest = false))
            }
        }
        onNodeWithTag("plan-open").assertIsDisplayed()
        onNodeWithTag("plan-approve").assertDoesNotExist()
        onNodeWithText("# Feature").assertDoesNotExist()
    }

    @Test
    fun `switched off planning gives no decisions`() = runSkikoComposeUiTest(size = Size(600f, 600f)) {
        setContent {
            HbTheme(darkTheme = false) {
                StudioPlanCard(
                    PlanProposalUi(3, ""),
                    plan,
                    {},
                    {},
                    {},
                    decisions = PlanCardDecisions(isEnabled = false),
                )
            }
        }
        onNodeWithTag("plan-approve").assertDoesNotExist()
        onNodeWithTag("plan-implement").assertDoesNotExist()
        onNodeWithTag("plan-open").assertIsDisplayed()
    }

    @Test
    fun `a busy chat cannot implement its plan`() = runSkikoComposeUiTest(size = Size(600f, 600f)) {
        setContent {
            HbTheme(darkTheme = false) {
                StudioPlanCard(PlanProposalUi(3, ""), plan, {}, {}, {}, decisions = PlanCardDecisions(isBusy = true))
            }
        }
        onNodeWithTag("plan-implement").assertIsNotEnabled()
        onNodeWithTag("plan-approve").assertIsEnabled()
    }

    @Test
    fun `the last proposal of each revision shows its decisions`() {
        fun reply(id: String, revision: Long?) = MessageUi.Reply(
            id,
            Instant.DISTANT_PAST,
            "",
            persistentListOf(
                ToolUi(id, "plan_propose", ToolStatusUi.Done, "", null, plan = PlanProposalUi(revision, "")),
            ),
            isStreaming = false,
        )
        val latest = latestPlanProposals(listOf(reply("a", 2), reply("b", 3), reply("c", 3), reply("d", null)))
        assertEquals(mapOf(2L to "a", 3L to "c"), latest)
    }

    @Test
    fun `refining keeps the typed draft`() {
        assertEquals("Refine: ", planRefineDraft("", "Refine: "))
        assertEquals("Refine: add tests", planRefineDraft("  add tests", "Refine: "))
        assertEquals("Refine: add tests", planRefineDraft("Refine: add tests", "Refine: "))
    }

    @Test
    fun `an unedited editor follows a newer revision`() = runSkikoComposeUiTest(size = Size(900f, 900f)) {
        var shown by mutableStateOf(plan)
        setContent {
            HbTheme(darkTheme = false) {
                StudioPlanEditor(shown, { _, _ -> }, {})
            }
        }
        shown = plan.copy(revision = 4, text = "# Agent")
        waitForIdle()
        editor().assertTextContains("# Agent")
        onNodeWithTag("plan-reload").assertDoesNotExist()
        onNodeWithTag("plan-save").assertIsNotEnabled()
    }

    @Test
    fun `the editor saves from its revision and closes once the plan shows the edit`() =
        runSkikoComposeUiTest(size = Size(900f, 900f)) {
            var shown by mutableStateOf(plan)
            val saved = mutableListOf<Pair<String, Long>>()
            var dismissed = 0
            setContent {
                HbTheme(darkTheme = false) {
                    StudioPlanEditor(shown, { text, base -> saved += text to base }, { dismissed++ })
                }
            }
            editor().performTextReplacement("# Mine")
            onNodeWithTag("plan-save").performClick()
            assertEquals("# Mine" to 3L, saved.single())
            assertEquals(0, dismissed)
            shown = plan.copy(revision = 4, text = "# Mine")
            waitForIdle()
            assertTrue(dismissed > 0)
        }

    @Test
    fun `an edit over a newer revision overwrites it only explicitly`() = runSkikoComposeUiTest(
        size = Size(900f, 900f),
    ) {
        var shown by mutableStateOf(plan)
        val saved = mutableListOf<Pair<String, Long>>()
        var dismissed = 0
        setContent {
            HbTheme(darkTheme = false) {
                StudioPlanEditor(shown, { text, base -> saved += text to base }, { dismissed++ })
            }
        }
        editor().performTextReplacement("# Mine")
        shown = plan.copy(revision = 4, text = "# Agent")
        waitForIdle()
        onNodeWithTag("plan-reload").assertIsDisplayed()
        editor().assertTextContains("# Mine")
        onNodeWithTag("plan-save").performClick()
        assertEquals("# Mine" to 4L, saved.single())
        assertEquals(0, dismissed)
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

    @Test
    fun `profile security replaces the approval choice of the implementation`() = runSkikoComposeUiTest(
        size = Size(900f, 1200f),
    ) {
        val sent = mutableListOf<StudioIntent.Plan>()
        val offer = PlanImplementationOffer(
            persistentListOf(ModelUi("a", "Alpha")),
            "a",
            ApprovalUi.AutoEdits,
            persistentListOf(PlanImplementationModeUi.Worktree),
            securityLevel = SecurityLevelUi.Automatic,
        )
        setContent {
            HbTheme(darkTheme = false) {
                StudioPlanImplementDialog(plan, offer, onImplement = { sent += it }, onDismiss = {}, sessionId = "chat")
            }
        }
        onNodeWithTag("plan-profile-security").assertIsNotEnabled()
        onNodeWithTag("plan-approval-AutoApprove").assertDoesNotExist()
        onNodeWithTag("plan-implement-confirm").performClick()
        val intent = sent.single() as StudioIntent.ImplementPlan
        assertEquals(ApprovalUi.AutoEdits, intent.approval)
        assertEquals(PlanImplementationModeUi.Worktree, intent.mode)
    }

    @Test
    fun `a busy chat does not implement and the chat effort is preselected`() = runSkikoComposeUiTest(
        size = Size(900f, 1200f),
    ) {
        val model = ModelUi(
            "a",
            "Alpha",
            reasoningEfforts = persistentListOf("low", "high"),
            defaultReasoningEffort = "low",
        )
        val offer = PlanImplementationOffer(
            persistentListOf(model),
            "a",
            ApprovalUi.Ask,
            persistentListOf(PlanImplementationModeUi.Chat),
            effort = "high",
            isBusy = true,
        )
        setContent {
            HbTheme(darkTheme = false) {
                StudioPlanImplementDialog(plan, offer, onImplement = {}, onDismiss = {}, sessionId = "chat")
            }
        }
        onNodeWithTag("plan-effort-high").assertIsSelected()
        onNodeWithTag("plan-implement-confirm").assertIsNotEnabled()
    }

    private fun SemanticsNodeInteractionsProvider.editor(): SemanticsNodeInteraction = onNode(hasSetTextAction())
}
