package io.aequicor.heartbeat.chromiumplugin.panel.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenui.StudioEmbed
import io.aequicor.heartbeat.feature.aistudio.screenui.StudioExits
import io.aequicor.heartbeat.feature.checklist.screenui.ChecklistScreen
import io.aequicor.heartbeat.feature.questionnaire.screenui.QuestionnaireScreen
import kotlinx.collections.immutable.toImmutableMap

/** Embeds draw the exact shared children and address every input to the owning handle instance. */
internal fun AiStudioScreenState.panelExits(onIntent: (AiStudioScreenIntent) -> Unit): StudioExits = StudioExits(
    questions = remoteEmbeds.questions.mapValues { (source, view) ->
        object : StudioEmbed {
            @Composable
            override fun Content(modifier: Modifier) {
                QuestionnaireScreen(
                    view.screen,
                    { onIntent(AiStudioScreenIntent.QuestionInput(source, view.instance, it)) },
                    modifier,
                )
            }
        }
    }.toImmutableMap(),
    checklists = remoteEmbeds.checklists.mapValues { (id, view) ->
        object : StudioEmbed {
            @Composable
            override fun Content(modifier: Modifier) {
                ChecklistScreen(
                    view.screen,
                    { onIntent(AiStudioScreenIntent.ChecklistInput(id, view.instance, it)) },
                    modifier,
                )
            }
        }
    }.toImmutableMap(),
)
