package io.aequicor.heartbeat.feature.questionnaire.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.machineSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Shows the pending questions of [source] (e.g. a chat session id) and lets the user answer them. */
@Serializable
@SerialName("questionnaire")
public data class QuestionnaireRoute(val source: String) : Route

/** Structured questions from agents replace plain permission buttons. */
public val QuestionnaireEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "questionnaire.enabled",
    description = "Опросник агента: выбор, свободный ввод и подтверждения",
    default = true,
)

/** Queue of questions awaiting the user in the profile. */
@Serializable
public sealed interface QuestionnaireState : MachineState {
    /** Nothing to answer. */
    @Serializable
    public data object Idle : QuestionnaireState

    /**
     * Questions in asking order; [submitting] were answered and wait for their source to withdraw them.
     * [generations] identifies each pending ask independently of question content. Automated answerers capture
     * the generation alongside the question and return it as [QuestionnaireIntent.Public.Answer.expectedGeneration].
     * The machine populates this map; the empty default preserves manually constructed states and legacy decoding.
     */
    @Serializable
    public data class Asking(
        val pending: List<Questionnaire>,
        val submitting: Set<QuestionnaireId> = emptySet(),
        val generations: Map<QuestionnaireId, String> = emptyMap(),
    ) : QuestionnaireState
}

/** Questions and answers. */
public sealed interface QuestionnaireIntent : MachineIntent {
    /** Intents from sources and the questionnaire screen. */
    public sealed interface Public : QuestionnaireIntent {
        /**
         * Adds [questionnaire]; asking an existing id again replaces it and reopens it for answers.
         * Construct a new intent for each ask, including identical replacements and retries after withdrawal.
         * Its nonce is allocated before dispatch so transitions remain pure and returning to Idle cannot reset it.
         */
        public data class Ask(val questionnaire: Questionnaire) : Public {
            // Preserve question-based intent equality; copy constructs a new ask with its own nonce.
            @OptIn(ExperimentalUuidApi::class)
            internal val generation: String = Uuid.random().toString()
        }

        /**
         * The user answers [id]; accepted once, while the question is pending and the answer fits it.
         * [expectedGeneration] additionally requires the exact ask observed by an automated answerer to remain
         * current at dispatch. Null preserves manual callers that answer the current question without this guard.
         * [expectedPendingGenerations] additionally requires every listed id to remain pending, not submitting,
         * and at its captured generation. Batch answerers exclude only their own already accepted answers;
         * a concurrent manual answer to any remaining batch question then rejects this answer atomically.
         */
        public data class Answer(
            val id: QuestionnaireId,
            val answer: io.aequicor.heartbeat.feature.questionnaire.api.Answer,
            val context: QuestionnaireAnswerContext? = null,
            val expectedGeneration: String? = null,
            val expectedPendingGenerations: Map<QuestionnaireId, String>? = null,
        ) : Public

        /** The source no longer needs [id] (answer delivered, turn ended or cancelled). */
        public data class Withdraw(val id: QuestionnaireId) : Public
    }

    /** The queue has no internal intents. */
    public sealed interface Internal : QuestionnaireIntent
}

/** The queue performs no IO. */
public sealed interface QuestionnaireEffect : MachineEffect

/** Events for sources. */
public sealed interface QuestionnaireOutput : MachineOutput {
    /** The user answered [questionnaire]; its source delivers the answer and then withdraws the question. */
    public data class Answered(
        val questionnaire: Questionnaire,
        val answer: Answer,
        val context: QuestionnaireAnswerContext? = null,
    ) : QuestionnaireOutput

    /** [id] left the queue. */
    public data class Withdrawn(val id: QuestionnaireId) : QuestionnaireOutput
}

/** Address of the profile questionnaire machine. */
public object QuestionnaireMachineKey : MachineKey<
    QuestionnaireState,
    QuestionnaireIntent,
    QuestionnaireIntent.Public,
    QuestionnaireEffect,
    QuestionnaireOutput,
> {
    override val name: String = "questionnaire"
}

/**
 * | From   | Intent   | Guard                                   | To                         | Output    |
 * |--------|----------|-----------------------------------------|----------------------------|-----------|
 * | Idle   | Ask      | —                                       | Asking([q])                | —         |
 * | Asking | Ask      | —                                       | Asking(q replaced/appended, not submitting) | — |
 * | Asking | Answer   | pending, not submitting, valid and current | Asking(+submitting)     | Answered  |
 * | Asking | Withdraw | pending, others remain                  | Asking(− q)                | Withdrawn |
 * | Asking | Withdraw | pending, last one                       | Idle                       | Withdrawn |
 *
 * Everything else is ignored. The machine is a pure queue without effects; answers stay pending until the
 * source withdraws them, so a failed delivery can re-ask the same id. Accepted answers relay native host context
 * unchanged in their output, without storing it in queue state. Each newly constructed Ask supplies a fresh
 * generation even for identical content or after Idle; guarded answers to a superseded ask are ignored atomically.
 * Optional batch guards also reject answers if any expected question was answered, withdrawn or replaced.
 * The machine lives in the profile scope, whose saved state is not persisted; open questions survive app restarts
 * through the questionnaire journal of the impl module, which asks them again (reopened) when the profile opens.
 */
public val QuestionnaireMachineSpec:
    MachineSpec<QuestionnaireState, QuestionnaireIntent, QuestionnaireEffect, QuestionnaireOutput> =
    machineSpec(QuestionnaireMachineKey, QuestionnaireState.Idle) {
        state<QuestionnaireState.Idle> {
            on<QuestionnaireIntent.Public.Ask> {
                goto<QuestionnaireState.Asking> {
                    QuestionnaireState.Asking(
                        pending = listOf(intent.questionnaire),
                        generations = mapOf(intent.questionnaire.id to intent.generation),
                    )
                }
            }
        }
        state<QuestionnaireState.Asking> {
            on<QuestionnaireIntent.Public.Ask> {
                stay { state.asked(intent) }
            }
            on<QuestionnaireIntent.Public.Answer>(guard = {
                intent.id !in state.submitting &&
                    (intent.expectedGeneration == null || state.generations[intent.id] == intent.expectedGeneration) &&
                    state.matchesPendingGenerations(intent.expectedPendingGenerations) &&
                    state.pending.any { it.id == intent.id && it.accepts(intent.answer) }
            }) {
                stay { state.copy(submitting = state.submitting + intent.id) }
                output {
                    QuestionnaireOutput.Answered(
                        state.pending.first { it.id == intent.id },
                        intent.answer,
                        intent.context,
                    )
                }
            }
            on<QuestionnaireIntent.Public.Withdraw>(guard = {
                state.pending.any { it.id == intent.id } && state.pending.size > 1
            }) {
                stay {
                    state.copy(
                        pending = state.pending.filterNot { it.id == intent.id },
                        submitting = state.submitting - intent.id,
                        generations = state.generations - intent.id,
                    )
                }
                output { QuestionnaireOutput.Withdrawn(intent.id) }
            }
            on<QuestionnaireIntent.Public.Withdraw>(guard = {
                state.pending.singleOrNull()?.id == intent.id
            }) {
                goto<QuestionnaireState.Idle> { QuestionnaireState.Idle }
                output { QuestionnaireOutput.Withdrawn(intent.id) }
            }
        }
    }

private fun QuestionnaireState.Asking.matchesPendingGenerations(expected: Map<QuestionnaireId, String>?): Boolean =
    expected == null || expected.all { (id, generation) ->
        id !in submitting && generations[id] == generation && pending.any { it.id == id }
    }

private fun QuestionnaireState.Asking.asked(intent: QuestionnaireIntent.Public.Ask): QuestionnaireState.Asking {
    val questionnaire = intent.questionnaire
    val index = pending.indexOfFirst { it.id == questionnaire.id }
    val updated = if (index < 0) {
        pending + questionnaire
    } else {
        pending.toMutableList().also { it[index] = questionnaire }
    }
    return copy(
        pending = updated,
        submitting = submitting - questionnaire.id,
        generations = generations + (questionnaire.id to intent.generation),
    )
}
