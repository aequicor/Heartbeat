package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.LearningCallUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.LearningKindUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.learning_kind_general
import io.aequicor.heartbeat.feature.aistudio.impl.resources.learning_kind_model
import io.aequicor.heartbeat.feature.aistudio.impl.resources.learning_kind_skill
import io.aequicor.heartbeat.feature.aistudio.impl.resources.learning_tool_remember
import io.aequicor.heartbeat.feature.aistudio.impl.resources.learning_tool_skill
import org.jetbrains.compose.resources.stringResource

/** Localized copy of self-learning tool cards, prepared in composition. */
@Immutable
internal data class LearningLabels(
    val rememberTitle: String,
    val skillTitle: String,
    val general: String,
    val model: String,
    val skill: String,
) {
    /** Card title replacing the raw tool name. */
    fun title(call: LearningCallUi): String = if (call.isSkillLoad) skillTitle else rememberTitle

    /** Collapsed line: the instruction kind and its title. */
    fun summary(call: LearningCallUi): String = listOfNotNull(
        call.kind?.let {
            when (it) {
                LearningKindUi.General -> general
                LearningKindUi.Model -> model
                LearningKindUi.Skill -> skill
            }
        },
        call.title.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}

/** Self-learning card copy in the current language. */
@Composable
internal fun studioLearningLabels(): LearningLabels = LearningLabels(
    rememberTitle = stringResource(Res.string.learning_tool_remember),
    skillTitle = stringResource(Res.string.learning_tool_skill),
    general = stringResource(Res.string.learning_kind_general),
    model = stringResource(Res.string.learning_kind_model),
    skill = stringResource(Res.string.learning_kind_skill),
)
