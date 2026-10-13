@file:UseSerializers(ImmutableMapSerializer::class)

package io.aequicor.heartbeat.feature.aistudio.screenmodel

import io.aequicor.heartbeat.feature.checklist.screenmodel.ChecklistScreenState
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenState
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Committed children of one remote studio. [revision] orders full updates, including removed children. */
@Serializable
data class StudioEmbedViews(
    val revision: Long = 0,
    val questions: ImmutableMap<String, StudioQuestionView> = persistentMapOf(),
    val checklists: ImmutableMap<String, StudioChecklistView> = persistentMapOf(),
)

/** [instance] prevents queued input from reaching a newly opened handle of the same source. */
@Serializable
data class StudioQuestionView(val instance: String, val revision: Long, val screen: QuestionnaireScreenState)

/** [instance] prevents queued input from reaching a newly opened handle of the same card. */
@Serializable
data class StudioChecklistView(val instance: String, val revision: Long, val screen: ChecklistScreenState)
