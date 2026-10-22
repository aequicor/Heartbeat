package io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.questionnaire.api.Answer
import io.aequicor.heartbeat.feature.questionnaire.api.Question
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireAnswerContext
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireId
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireOutput
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireRoute
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireState
import io.aequicor.heartbeat.feature.questionnaire.impl.di.scope.QuestionnaireScope
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.ChoiceUi
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionKindUi
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionUi
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenAction
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenIntent
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenState
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.predictDraft
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.job
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.plugins.reduce

private typealias QuestionnaireMachine = Machine<QuestionnaireState, QuestionnaireIntent, QuestionnaireOutput>

/** Screen store of one source: reflects its pending questions and keeps unsent drafts. */
@SingleIn(QuestionnaireScope::class)
@Inject
internal class QuestionnaireModel(
    machine: QuestionnaireMachine,
    route: QuestionnaireRoute,
    @ForScope(QuestionnaireScope::class) scope: ScopeHandle,
    factory: HeartbeatStoreFactory,
) {
    private val lifetime = scope.coroutineScope.coroutineContext.job
    private val log = Log.tag("Questionnaire")
    private val source = route.source

    val store = factory.create<QuestionnaireScreenState, QuestionnaireModelInput, QuestionnaireScreenAction>(
        name = "Questionnaire",
        initial = QuestionnaireScreenState().reflectQueue(machine.state.value, source),
        onError = { this },
    ) {
        reflect(machine) { reflectQueue(machine.state.value, source) }
        install {
            name = "committed-revision"
            onState { old, new ->
                val normalized = new.copy(revision = old.revision)
                if (normalized == old) old else normalized.copy(revision = old.revision + 1)
            }
        }
        reduce { input ->
            val intent = input.intent
            try {
                run handle@{
                    val isShown = (machine.state.value as? QuestionnaireState.Asking)?.pending.orEmpty()
                        .any { it.source == source && it.id.value == intent.questionId }
                    if (!isShown) {
                        log.w { "Question input does not belong to this screen" }
                        return@handle
                    }
                    val answer: Answer? = when (intent) {
                        is QuestionnaireScreenIntent.ShowQuestion, is QuestionnaireScreenIntent.ToggleChoice -> {
                            updateState { predictDraft(intent) }
                            null
                        }

                        is QuestionnaireScreenIntent.TextChanged -> {
                            updateState { predictDraft(intent) }
                            null
                        }

                        is QuestionnaireScreenIntent.Confirm -> {
                            var isEditable = false
                            withState {
                                isEditable =
                                    questions.firstOrNull { it.id == intent.questionId }?.isFreeTextAllowed == true
                            }
                            if (isEditable) {
                                updateState { predictDraft(intent) }
                                null
                            } else {
                                Answer.Confirmed(intent.isConfirmed)
                            }
                        }

                        is QuestionnaireScreenIntent.Submit -> {
                            var draft: Answer? = null
                            withState { draft = questions.firstOrNull { it.id == intent.questionId }?.draftAnswer() }
                            if (draft == null) log.w { "Submit ignored: the answer is not ready" }
                            draft
                        }

                        is QuestionnaireScreenIntent.Skip -> Answer.Skipped
                    }
                    if (answer != null) {
                        log.i { "Answer question kind=${answer::class.simpleName.orEmpty()}" }
                        val id = QuestionnaireId(intent.questionId)
                        sendTo(machine, QuestionnaireIntent.Public.Answer(id, answer, input.context)) { rejected ->
                            log.w { "Answer was not accepted: $rejected" }
                        }
                    }
                }
                // Machine.send commits before returning, while reflect may still be queued on another coroutine.
                updateState { reflectQueue(machine.state.value, source) }
                withState { input.completion?.complete(this) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                input.completion?.completeExceptionally(error)
                throw error
            } finally {
                input.completion?.cancel()
            }
        }
    }

    /** Local input uses the same reducer as a remote dispatch. */
    fun intent(intent: QuestionnaireScreenIntent) = store.intent(QuestionnaireModelInput(intent))

    /** Waits for committed reduction, and is cancelled when this screen closes. */
    suspend fun dispatch(
        intent: QuestionnaireScreenIntent,
        context: QuestionnaireAnswerContext? = null,
    ): QuestionnaireScreenState {
        val completion = CompletableDeferred<QuestionnaireScreenState>(lifetime)
        try {
            store.intent(QuestionnaireModelInput(intent, completion, context))
            return completion.await()
        } finally {
            completion.cancel()
        }
    }

    init {
        store.start(scope.coroutineScope)
    }
}

/** Mirrors the source's queue while keeping drafts of questions that are still pending. */
internal fun QuestionnaireScreenState.reflectQueue(
    state: QuestionnaireState,
    source: String,
): QuestionnaireScreenState {
    val asking = state as? QuestionnaireState.Asking ?: return copy(
        questions = emptyList<QuestionUi>().toImmutableList(),
        activeQuestionId = null,
    )
    val drafts = questions.associateBy { it.id }
    val shown = asking.pending.filter { it.source == source }.map { questionnaire ->
        val draft = drafts[questionnaire.id.value]
        questionnaire.toUi(isSubmitting = questionnaire.id in asking.submitting).let { ui ->
            if (draft == null || draft.kind != ui.kind || draft.isFreeTextAllowed != ui.isFreeTextAllowed) {
                ui
            } else {
                ui.copy(
                    selected = draft.selected,
                    text = draft.text,
                    isConfirmed = draft.isConfirmed,
                )
            }
        }
    }
    val previousIndex = questions.indexOfFirst { it.id == activeQuestionId }.coerceAtLeast(0)
    val active = shown.firstOrNull { it.id == activeQuestionId && !it.isSubmitting }
        ?: shown.drop(previousIndex).firstOrNull { !it.isSubmitting }
        ?: shown.firstOrNull { !it.isSubmitting }
        ?: shown.firstOrNull()
    return copy(questions = shown.toImmutableList(), activeQuestionId = active?.id)
}

private fun Questionnaire.toUi(isSubmitting: Boolean) = QuestionUi(
    id = id.value,
    title = title,
    description = description,
    kind = when (val question = question) {
        is Question.Confirm -> QuestionKindUi.Confirm(question.yes, question.no)

        is Question.SingleChoice -> QuestionKindUi.Choice(question.choices.toUi(), isMultiple = false)

        is Question.MultiChoice ->
            QuestionKindUi.Choice(question.choices.toUi(), isMultiple = true, min = question.min, max = question.max)

        is Question.FreeText -> QuestionKindUi.Text(question.placeholder, question.isMultiline)
    },
    isSkippable = isSkippable,
    isSubmitting = isSubmitting,
    isFreeTextAllowed = isFreeTextAllowed,
)

private fun List<io.aequicor.heartbeat.feature.questionnaire.api.Choice>.toUi() =
    map { ChoiceUi(it.id, it.title) }.toImmutableList()

internal fun QuestionUi.draftAnswer(): Answer? = when (val kind = kind) {
    is QuestionKindUi.Confirm -> isConfirmed?.let { Answer.Confirmed(it, text.takeIf(String::isNotBlank)) }
        ?: Answer.Text(text)

    is QuestionKindUi.Choice -> if (isFreeTextAllowed && selected.isEmpty() && text.isNotBlank()) {
        Answer.Text(text)
    } else {
        Answer.Selected(
            kind.choices.map { it.id }.filter { it in selected },
            text.takeIf { isFreeTextAllowed && it.isNotBlank() },
        )
    }

    is QuestionKindUi.Text -> Answer.Text(text)
}.takeIf { isAnswerReady }

/** Internal queue envelope; completion never travels over the wire. */
internal data class QuestionnaireModelInput(
    val intent: QuestionnaireScreenIntent,
    val completion: CompletableDeferred<QuestionnaireScreenState>? = null,
    val context: QuestionnaireAnswerContext? = null,
) : MVIIntent
