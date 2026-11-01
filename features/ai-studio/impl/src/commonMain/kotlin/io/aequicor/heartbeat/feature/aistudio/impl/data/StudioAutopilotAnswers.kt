package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.impl.domain.toQuestionnaire
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationAnswer
import io.aequicor.heartbeat.feature.questionnaire.api.Answer
import io.aequicor.heartbeat.feature.questionnaire.api.Question
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireAnswerContext
import io.aequicor.heartbeat.feature.questionnaire.api.accepts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A generated answer must fit the original structured question; it cannot turn a choice into arbitrary text. */
internal fun Questionnaire.autopilotAnswer(value: ContinuationAnswer, permission: StudioPermission? = null): Answer? {
    if ((value.text?.length ?: 0) > MAX_ANSWER_CHARS || value.selected.size > MAX_SELECTIONS) return null
    permissionConfirmation(value, permission)?.let { return it }
    val answer = when (question) {
        is Question.Confirm -> value.confirmed?.takeIf {
            value.selected.isEmpty() && value.text == null
        }?.let {
            Answer.Confirmed(
                it,
            )
        }

        is Question.SingleChoice, is Question.MultiChoice -> value.choiceAnswer()

        is Question.FreeText -> value.text?.takeIf {
            it.isNotBlank() && value.selected.isEmpty() &&
                value.confirmed == null
        }?.let(
            Answer::Text,
        )
    }
    return answer?.takeIf { accepts(it) }
}

/** Native two-option permissions can be returned by exact option id instead of the UI's Confirm boolean. */
private fun Questionnaire.permissionConfirmation(value: ContinuationAnswer, permission: StudioPermission?): Answer? {
    if (question !is Question.Confirm) return null
    if (value.confirmed != null || value.text != null || value.selected.size != 1) return null
    if (permission == null || permission.input != null) return null
    if (permission.options.size != 2 || permission.toQuestionnaire() != this) return null
    val id = value.selected.single()
    val option = permission.options.singleOrNull { it.id == id }?.takeIf { it.isOneTime } ?: return null
    return Answer.Confirmed(permission.options.indexOf(option) == 0)
}

/** The profile keeps answering live requests even when no studio screen is mounted. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class StudioAutopilotStartup(
    private val autopilot: Lazy<StudioAutopilotRequests>,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : ProfileStartup {
    override fun start() {
        profile.coroutineScope.launch {
            try {
                autopilot.value.observeQuestions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.tag("StudioAutopilot").e(e) { "Pending request observer stopped" }
            }
        }
    }
}

private const val MAX_ANSWER_CHARS = 8000
private const val MAX_SELECTIONS = 100

private fun ContinuationAnswer.choiceAnswer(): Answer? {
    if (confirmed != null) return null
    return when {
        selected.isNotEmpty() && text == null -> Answer.Selected(selected)
        selected.isEmpty() -> text?.let(Answer::Text)
        else -> null
    }
}

/** Trusted host provenance, never decoded from model output or screen JSON. */
internal class AutopilotQuestionContext(val permission: StudioPermission?) : QuestionnaireAnswerContext

/** Profile-owned live request observer, supplied through the same scoped continuation host binding. */
internal fun interface StudioAutopilotRequests {
    suspend fun observeQuestions()
}
