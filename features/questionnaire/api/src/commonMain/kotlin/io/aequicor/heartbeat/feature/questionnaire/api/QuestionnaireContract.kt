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

/** Shows the pending questions of [source] (e.g. a chat session id) and lets the user answer them. */
@Serializable
@SerialName("questionnaire")
public data class QuestionnaireRoute(val source: String) : Route

/** Structured questions from agents replace plain permission buttons. */
public val QuestionnaireEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "questionnaire.enabled",
    description = "Опросник агента: выбор, свободный ввод и подтверждения",
    default = false,
)

/** Queue of questions awaiting the user in the profile. */
@Serializable
public sealed interface QuestionnaireState : MachineState {
    /** Nothing to answer. */
    @Serializable
    public data object Idle : QuestionnaireState

    /** Questions in asking order; [submitting] were answered and wait for their source to withdraw them. */
    @Serializable
    public data class Asking(val pending: List<Questionnaire>, val submitting: Set<QuestionnaireId> = emptySet()) :
        QuestionnaireState
}

/** Questions and answers. */
public sealed interface QuestionnaireIntent : MachineIntent {
    /** Intents from sources and the questionnaire screen. */
    public sealed interface Public : QuestionnaireIntent {
        /** Adds [questionnaire]; asking an existing id again replaces it and reopens it for answers. */
        public data class Ask(val questionnaire: Questionnaire) : Public

        /** The user answers [id]; accepted once, while the question is pending and the answer fits it. */
        public data class Answer(
            val id: QuestionnaireId,
            val answer: io.aequicor.heartbeat.feature.questionnaire.api.Answer,
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
    public data class Answered(val questionnaire: Questionnaire, val answer: Answer) : QuestionnaireOutput

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
 * | Asking | Answer   | pending, not submitting, answer accepted | Asking(+submitting)       | Answered  |
 * | Asking | Withdraw | pending, others remain                  | Asking(− q)                | Withdrawn |
 * | Asking | Withdraw | pending, last one                       | Idle                       | Withdrawn |
 *
 * Everything else is ignored. The machine is a pure queue without effects; answers stay pending until the
 * source withdraws them, so a failed delivery can re-ask the same id.
 * Restoration keeps every pending question and reopens submitted ones: a delivery does not survive the process.
 */
public val QuestionnaireMachineSpec:
    MachineSpec<QuestionnaireState, QuestionnaireIntent, QuestionnaireEffect, QuestionnaireOutput> =
    machineSpec(QuestionnaireMachineKey, QuestionnaireState.Idle) {
        state<QuestionnaireState.Idle> {
            on<QuestionnaireIntent.Public.Ask> {
                goto<QuestionnaireState.Asking> { QuestionnaireState.Asking(listOf(intent.questionnaire)) }
            }
        }
        state<QuestionnaireState.Asking> {
            on<QuestionnaireIntent.Public.Ask> {
                stay { state.asked(intent.questionnaire) }
            }
            on<QuestionnaireIntent.Public.Answer>(guard = {
                intent.id !in state.submitting &&
                    state.pending.any { it.id == intent.id && it.accepts(intent.answer) }
            }) {
                stay { state.copy(submitting = state.submitting + intent.id) }
                output { QuestionnaireOutput.Answered(state.pending.first { it.id == intent.id }, intent.answer) }
            }
            on<QuestionnaireIntent.Public.Withdraw>(guard = {
                state.pending.any { it.id == intent.id } && state.pending.size > 1
            }) {
                stay {
                    state.copy(
                        pending = state.pending.filterNot { it.id == intent.id },
                        submitting = state.submitting - intent.id,
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
        persist(QuestionnaireState.serializer()) { saved ->
            when (saved) {
                QuestionnaireState.Idle -> restore(saved)
                is QuestionnaireState.Asking -> restore(saved.copy(submitting = emptySet()))
            }
        }
    }

private fun QuestionnaireState.Asking.asked(questionnaire: Questionnaire): QuestionnaireState.Asking {
    val index = pending.indexOfFirst { it.id == questionnaire.id }
    val updated = if (index < 0) {
        pending + questionnaire
    } else {
        pending.toMutableList().also { it[index] = questionnaire }
    }
    return copy(pending = updated, submitting = submitting - questionnaire.id)
}
