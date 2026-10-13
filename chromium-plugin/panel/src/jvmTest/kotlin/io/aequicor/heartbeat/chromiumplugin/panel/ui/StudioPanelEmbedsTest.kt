package io.aequicor.heartbeat.chromiumplugin.panel.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioChecklistView
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioEmbedViews
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioQuestionView
import io.aequicor.heartbeat.feature.checklist.screenmodel.ChecklistPhaseUi
import io.aequicor.heartbeat.feature.checklist.screenmodel.ChecklistScreenIntent
import io.aequicor.heartbeat.feature.checklist.screenmodel.ChecklistScreenState
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionKindUi
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionUi
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenIntent
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class StudioPanelEmbedsTest {
    @Test
    fun `shared child UI sends answers to the exact remote source and instance`() =
        runSkikoComposeUiTest(size = Size(420f, 800f)) {
            val sent = mutableListOf<AiStudioScreenIntent>()
            val question = QuestionUi("question", "Continue?", null, QuestionKindUi.Confirm("Yes", "No"), false, false)
            val embeds = StudioEmbedViews(
                questions = persistentMapOf(
                    "chat" to StudioQuestionView(
                        "question-instance",
                        1,
                        QuestionnaireScreenState(persistentListOf(question)),
                    ),
                ),
                checklists = persistentMapOf(
                    "card" to StudioChecklistView(
                        "checklist-instance",
                        2,
                        ChecklistScreenState(
                            title = "Checklist",
                            phase = ChecklistPhaseUi.Open,
                            isCompletionAllowed = true,
                        ),
                    ),
                ),
            )
            val exits = AiStudioScreenState(remoteEmbeds = embeds).panelExits(sent::add)
            setContent {
                HbTheme {
                    HbColumn {
                        exits.questions.getValue("chat").Content(Modifier)
                        exits.checklists.getValue("card").Content(Modifier)
                    }
                }
            }
            onNodeWithTag("question-yes-question").performClick()
            onNodeWithTag("checklist-complete").performClick()
            assertEquals(
                listOf<AiStudioScreenIntent>(
                    AiStudioScreenIntent.QuestionInput(
                        "chat",
                        "question-instance",
                        QuestionnaireScreenIntent.Confirm("question", true),
                    ),
                    AiStudioScreenIntent.ChecklistInput("card", "checklist-instance", ChecklistScreenIntent.Complete),
                ),
                sent,
            )
        }
}
