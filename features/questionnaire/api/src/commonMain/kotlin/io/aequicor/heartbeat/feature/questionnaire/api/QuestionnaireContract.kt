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
    /** Pristine queue. After the first mutation an empty [Asking] retains publication history. */
    @Serializable
    public data object Idle : QuestionnaireState

    /**
     * Questions in asking order; [submitting] were answered and wait for their source to withdraw them.
     * [generations] identifies each pending ask independently of question content. Automated answerers capture
     * the generation alongside the question and return it as [QuestionnaireIntent.Public.Answer.expectedGeneration].
     * The machine populates this map; the empty default preserves manually constructed states and legacy decoding.
     * Empty [pending] is also an idle queue: consumers must inspect the queue, not compare with [Idle].
     * [publications] survives the last withdrawal; [preparing] holds successful delivery gates, bounded by pending.
     * [deliveryReceipts] owns accepted deliveries until recovery, invalidation, or token acknowledgement.
     * [autoBarrier] retains manual-source exclusions for the entire lifetime of this machine.
     * [manualPreparations] reserves manual admission attempts while durable revocation runs. They remain outside
     * [submitting] so journal snapshots retain the question until revocation completes. No Answered output exists
     * before that completion; lost output or a later crash cannot restore the old human context.
     * All metadata belongs to this machine instance and is discarded with its profile scope.
     */
    @Serializable
    public data class Asking(
        val pending: List<Questionnaire>,
        val submitting: Set<QuestionnaireId> = emptySet(),
        val generations: Map<QuestionnaireId, String> = emptyMap(),
        val publications: QuestionnairePublicationHistory = QuestionnairePublicationHistory(),
        val preparing: Set<QuestionnaireId> = emptySet(),
        val deliveryReceipts: Map<QuestionnaireId, QuestionnaireDeliveryReceipt> = emptyMap(),
        val autoBarrier: QuestionnaireAutoBarrier = QuestionnaireAutoBarrier(),
        val manualPreparations: Map<QuestionnaireId, String> = emptyMap(),
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
         * [expectedGeneration] makes replacement conditional on the current publication. [isAbsentAllowed] also
         * permits restoring a withdrawn delivery only while retained history still proves it was the latest
         * publication of this id. Unknown or evicted history never authorizes a conditional ask.
         */
        public data class Ask(
            val questionnaire: Questionnaire,
            val expectedGeneration: String? = null,
            val isAbsentAllowed: Boolean = false,
        ) : Public {
            // Preserve question-based intent equality; copy constructs a new ask with its own nonce.
            /** Identity of this publication, available to its owner before dispatch. */
            @OptIn(ExperimentalUuidApi::class)
            public val generation: String = Uuid.random().toString()
        }

        /**
         * Restores a journal entry only if this machine has never touched its id. The check and publication are
         * one transition: do not replace this with a state read followed by Ask. Duplicate restores are ignored.
         * Exact touched-id exclusions survive generation eviction until FinishInitialRestore. After that seal every
         * Restore is ignored, including delayed requests from overlapping journal loads. Live ids are never restored.
         */
        public data class Restore(val questionnaire: Questionnaire) : Public {
            /** Fresh identity of a restored publication, allocated outside transition evaluation. */
            @OptIn(ExperimentalUuidApi::class)
            public val generation: String = Uuid.random().toString()
        }

        /**
         * Seals initial restoration and clears exact exclusions in one transition. Send only after a successful
         * durable load and processing every entry without NotRunning or cancellation. Repeated seals are ignored;
         * a journal reopening on this machine mirrors its queue without restoring stale durable entries.
         */
        public data object FinishInitialRestore : Public

        /**
         * Atomically claims preparation for an answered publication. Only SendResult.Accepted authorizes IO.
         * Send immediately before preparation; never infer permission from a state snapshot or Answered output.
         * Ask or Withdraw processed first invalidates the claim. Once accepted, a later replacement cannot undo
         * external work: it only prevents old completion intents from changing the replacement. One gate per
         * generation is accepted; duplicate collectors must not prepare or deliver after an ignored gate.
         * Reserves receipt capacity before IO. On refusal, ReopenUndelivered may reopen only an unclaimed answer.
         */
        public data class BeginDelivery(val id: QuestionnaireId, val expectedGeneration: String) : Public

        /**
         * Reopens only the matching pending answer when no delivery has claimed it. Use after a refused
         * BeginDelivery or failure before admission; never infer the refusal reason from a state snapshot.
         * The submitting and not-preparing guards are atomic with reopening. A concurrent successful gate,
         * replacement, withdrawal, or receipt makes this intent harmless. A fresh generation invalidates late
         * BeginDelivery attempts for the refused answer. This cannot recover an uncertain committed gate:
         * without ownership proof, leave preparing untouched and reconcile with its owner.
         */
        public data class ReopenUndelivered(val id: QuestionnaireId, val expectedGeneration: String) : Public {
            /** Identity of the retry publication, allocated outside transition evaluation. */
            @OptIn(ExperimentalUuidApi::class)
            public val generation: String = Uuid.random().toString()
        }

        /**
         * Removes the gated publication and atomically retains a receipt. Only Accepted permits final delivery.
         * Keep this intent's [receiptToken] BEFORE sending (send may be cancelled after the transition committed).
         * Capacity exhaustion rejects acceptance without removing the question or evicting an older receipt.
         * Final success must send CompleteDelivery; failure/cancellation sends FailDelivery with this token.
         */
        public data class AcceptDelivery(val id: QuestionnaireId, val expectedGeneration: String) : Public {
            /** Unique ownership of this acceptance, allocated outside transition evaluation. */
            @OptIn(ExperimentalUuidApi::class)
            public val receiptToken: String = Uuid.random().toString()
        }

        /** Releases only the matching receipt after final success, without touching another publication. */
        public data class CompleteDelivery(val id: QuestionnaireId, val receiptToken: String) : Public

        /**
         * Cancels recovery of an accepted delivery only with its matching receipt token. Does not withdraw a
         * pending publication or affect a replacement receipt. Before acceptance, use generation-guarded Withdraw.
         * This revokes in-memory recovery authority; it cannot cancel external delivery already in progress.
         */
        public data class CancelDelivery(val id: QuestionnaireId, val receiptToken: String) : Public

        /**
         * Reopens a failed gate as a fresh publication. Retrying requires a new Answered and BeginDelivery;
         * delayed AcceptDelivery or FailDelivery from the previous attempt can never affect the retry.
         * After successful AcceptDelivery, [receiptToken] is mandatory and must match the retained receipt.
         * A token also permits failure before acceptance committed, making cancellation at send safe to recover.
         * Accepted replacement, unconditional Withdraw, or matching CancelDelivery invalidates recovery.
         * Rejected Ask, Restore, and conditional Withdraw leave receipts and all other state unchanged.
         */
        public data class FailDelivery(
            val id: QuestionnaireId,
            val expectedGeneration: String,
            val receiptToken: String? = null,
        ) : Public {
            /** Identity of the retry publication. */
            @OptIn(ExperimentalUuidApi::class)
            public val generation: String = Uuid.random().toString()
        }

        /**
         * The user answers [id] once while the question is pending and the answer fits it. A substantive Manual
         * answer first reserves an admission attempt: SendResult.Accepted does not yet mean final acceptance or
         * delivery. PrepareManual must durably revoke the source's context before ManualPrepared emits Answered.
         * Failure releases the reservation for a manual retry; no collector is needed for the durable barrier.
         * [expectedGeneration] additionally requires the exact ask observed by an automated answerer to remain
         * current at dispatch. Null preserves manual callers that answer the current question without this guard.
         * [expectedPendingGenerations] additionally requires every listed id to remain pending, not submitting,
         * and at its captured generation. Batch answerers exclude only their own already accepted answers;
         * a concurrent manual answer to any remaining batch question then rejects this answer atomically.
         * [origin] is native host-only provenance, never supplied by model/screen JSON. Auto requires a non-null
         * generation and an unblocked source. A reserved Manual non-Skipped answer atomically blocks that source
         * across all batches for this machine's lifetime. Opaque [context] is still relayed without interpretation.
         * This supplements, and never replaces or unlocks, the owner's durable context lock.
         */
        public data class Answer(
            val id: QuestionnaireId,
            val answer: io.aequicor.heartbeat.feature.questionnaire.api.Answer,
            val context: QuestionnaireAnswerContext? = null,
            val expectedGeneration: String? = null,
            val expectedPendingGenerations: Map<QuestionnaireId, String>? = null,
            val origin: QuestionnaireAnswerOrigin = QuestionnaireAnswerOrigin.Manual,
        ) : Public {
            /** Unique admission attempt; allocated outside pure transition evaluation. */
            @OptIn(ExperimentalUuidApi::class)
            public val admissionToken: String = Uuid.random().toString()
        }

        /**
         * The source no longer needs [id]. [expectedGeneration] atomically limits withdrawal to that publication;
         * null explicitly withdraws the current question and invalidates any receipt, even for an absent id.
         * Conditional withdrawal requires a pending publication; use CancelDelivery to cancel an accepted receipt.
         */
        public data class Withdraw(val id: QuestionnaireId, val expectedGeneration: String? = null) : Public
    }

    /** Results of durable manual-answer admission. */
    public sealed interface Internal : QuestionnaireIntent {
        /** Emitted only after the source has durably revoked its prior human context. */
        public data class ManualPrepared(val preparation: QuestionnaireEffect.PrepareManual) : Internal

        /** Failed admission permits a manual retry but keeps automatic answers blocked. */
        public data class ManualPreparationFailed(val id: QuestionnaireId, val token: String) : Internal
    }
}

/** Durable preparation must finish before an answer becomes submitting or produces output. */
public sealed interface QuestionnaireEffect : MachineEffect {
    /** Captured publication and attempt; stale completion cannot accept a replacement or a retry. */
    public data class PrepareManual(
        val questionnaire: Questionnaire,
        val generation: String?,
        val answer: QuestionnaireIntent.Public.Answer,
    ) : QuestionnaireEffect
}

/** Events for sources. */
public sealed interface QuestionnaireOutput : MachineOutput {
    /**
     * The user answered [questionnaire] at [generation]. Owners must claim BeginDelivery before preparation,
     * retain AcceptDelivery.receiptToken before accepting, and acknowledge final success with CompleteDelivery.
     * FailDelivery recovers either a preparing publication or an accepted receipt (with its matching token).
     * A state read is not a delivery gate. Null generation is possible only for legacy manually constructed states.
     */
    public data class Answered(
        val questionnaire: Questionnaire,
        val answer: Answer,
        val context: QuestionnaireAnswerContext? = null,
        val generation: String? = null,
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
 * | From | Intent | Guard | To / output |
 * |------|--------|-------|-------------|
 * | Idle | Ask / Restore | unconditional Ask or Restore | Asking with fresh publication |
 * | Idle | Withdraw | unconditional | empty Asking with touched id |
 * | Asking | Ask | unconditional or matching current / retained absent generation | replace and reopen |
 * | Asking | Restore | initial restore safe and open and durable id untouched | append |
 * | Idle / Asking | FinishInitialRestore | initial restore safe and open | sealed Asking with empty touched |
 * | Asking | manual substantive Answer | pending, valid, generation/batch match | reserve, block Auto / PrepareManual |
 * | Asking | ManualPrepared | same publication and attempt | submitting / Answered |
 * | Asking | ManualPreparationFailed | same attempt | release reservation, retain Auto barrier |
 * | Asking | Auto or skipped Answer | pending, valid, generation/batch/origin match | submitting / Answered |
 * | Asking | BeginDelivery | current, submitting, not preparing, reserved receipt capacity | preparing |
 * | Asking | ReopenUndelivered | current, submitting, not preparing | reopen fresh |
 * | Asking | AcceptDelivery | current, preparing, receipt capacity | remove, retain receipt / Withdrawn |
 * | Asking | CompleteDelivery / CancelDelivery | matching receipt token | remove receipt |
 * | Asking | FailDelivery | current preparing OR matching receipt token and generation | reopen fresh |
 * | Asking | Withdraw | unconditional or matching pending generation | remove and retain history |
 *
 * Transitions are pure. Nonces are allocated by intent constructors, never by resolution. The pristine Idle
 * singleton is never re-entered: even an empty queue keeps bounded generations and the initial-restore seal.
 * Exact durable touched ids are needed only until the first successful journal reconciliation. If their bounded
 * history overflows before reconciliation, restoration and sealing stay disabled for this machine instance;
 * the journal must preserve the durable snapshot rather than overwrite it from an incomplete queue.
 * Unguarded withdrawal of an absent id records a touch, preventing a journal load racing that withdrawal from
 * resurrecting it. A missing or evicted record cannot authorize a conditional absent Ask.
 *
 * Delivery integration: collect outputs before answering; capture Answered.generation; send BeginDelivery on
 * the same machine and prepare only on Accepted. Construct AcceptDelivery and retain its receiptToken before
 * dispatch. Only Accepted permits final delivery; afterwards send CompleteDelivery(id, receiptToken). On failure
 * or cancellation send FailDelivery(id, generation, receiptToken) from a surviving cleanup scope: it handles both
 * sides of the acceptance boundary, consumes the receipt, and republishes with a new generation. Never use
 * generation-only absent Ask for this recovery. No receipt is evicted; BeginDelivery reserves capacity across
 * preparing publications and receipts, so acceptance cannot run out of room after preparation IO. A refused gate
 * may send ReopenUndelivered: only its still-pending unclaimed answer reopens, never another collector's gate.
 * Rejected Ask/Restore/conditional Withdraw return Ignored without mutation, including retained receipts.
 * Accepted replacements and unconditional Withdraw invalidate the receipt for their id. Owners cancelling an
 * accepted delivery use CancelDelivery(id, receiptToken); conditional Withdraw only targets pending publications.
 * No other id is affected. CompleteDelivery acknowledges final success; CancelDelivery revokes recovery authority.
 *
 * Limits: this machine has no persistence or transactional output outbox. Answered/Withdrawn can be lost on
 * collector cancellation or process death; accepted receipts and manual-source barriers do not survive restart.
 * Hosts must supply durable ownership, idempotent external delivery, crash reconciliation and cleanup on send
 * cancellation. A cancelled send can already have committed. Receipt recovery cannot undo external side effects
 * or prove they did not happen; after uncertain external success, reconcile before retrying. Keep the durable
 * context lock independently; native origin and this in-memory barrier cannot reconstruct it after a crash.
 *
 * Existing Ask, Answer, Withdraw and output shapes remain supported, including manual answers without a
 * generation. Use pending.isEmpty() for idle UI. New fields default for legacy decoding; no storage migration is
 * required because this profile machine is not persisted. The journal restores questions through Restore.
 */
public val QuestionnaireMachineSpec:
    MachineSpec<QuestionnaireState, QuestionnaireIntent, QuestionnaireEffect, QuestionnaireOutput> =
    machineSpec(QuestionnaireMachineKey, QuestionnaireState.Idle) {
        state<QuestionnaireState.Idle> {
            on<QuestionnaireIntent.Public.Ask>(guard = { intent.expectedGeneration == null }) {
                goto<QuestionnaireState.Asking> {
                    QuestionnaireState.Asking(emptyList()).asked(intent.questionnaire, intent.generation)
                }
            }
            on<QuestionnaireIntent.Public.FinishInitialRestore> {
                goto<QuestionnaireState.Asking> {
                    QuestionnaireState.Asking(
                        emptyList(),
                        publications = QuestionnairePublicationHistory().finishInitialRestore(),
                    )
                }
            }
            on<QuestionnaireIntent.Public.Restore>(guard = {
                !intent.questionnaire.id.value.startsWith(LIVE_QUESTION_ID_PREFIX)
            }) {
                goto<QuestionnaireState.Asking> {
                    QuestionnaireState.Asking(emptyList()).asked(intent.questionnaire, intent.generation)
                }
            }
            on<QuestionnaireIntent.Public.Withdraw>(guard = { intent.expectedGeneration == null }) {
                goto<QuestionnaireState.Asking> { QuestionnaireState.Asking(emptyList()).withdrawn(intent.id) }
            }
        }
        state<QuestionnaireState.Asking> {
            on<QuestionnaireIntent.Public.Ask>(guard = {
                state.canAsk(intent)
            }) {
                stay { state.asked(intent.questionnaire, intent.generation) }
            }
            on<QuestionnaireIntent.Public.FinishInitialRestore>(guard = {
                !state.publications.isInitialRestoreFinished && !state.publications.isInitialRestoreUnsafe
            }) {
                stay { state.copy(publications = state.publications.finishInitialRestore()) }
            }
            on<QuestionnaireIntent.Public.Restore>(guard = {
                state.canRestore(intent)
            }) {
                stay { state.asked(intent.questionnaire, intent.generation) }
            }
            on<QuestionnaireIntent.Public.Answer>(guard = {
                state.canAnswer(intent) && intent.isSubstantiveManual
            }) {
                stay {
                    state.copy(
                        manualPreparations = state.manualPreparations + (intent.id to intent.admissionToken),
                        autoBarrier = state.autoBarrier.block(state.pending.first { it.id == intent.id }.source),
                    )
                }
                effect {
                    QuestionnaireEffect.PrepareManual(
                        state.pending.first { it.id == intent.id },
                        state.generations[intent.id],
                        intent,
                    )
                }
            }
            on<QuestionnaireIntent.Internal.ManualPrepared>(guard = {
                state.matchesPreparation(intent.preparation)
            }) {
                stay {
                    val id = intent.preparation.answer.id
                    state.copy(submitting = state.submitting + id, manualPreparations = state.manualPreparations - id)
                }
                output {
                    val prepared = intent.preparation
                    QuestionnaireOutput.Answered(
                        prepared.questionnaire,
                        prepared.answer.answer,
                        prepared.answer.context,
                        prepared.generation,
                    )
                }
            }
            on<QuestionnaireIntent.Internal.ManualPreparationFailed>(guard = {
                state.manualPreparations[intent.id] == intent.token
            }) {
                stay { state.copy(manualPreparations = state.manualPreparations - intent.id) }
            }
            on<QuestionnaireIntent.Public.Answer>(guard = {
                state.canAnswer(intent) && !intent.isSubstantiveManual
            }) {
                stay { state.copy(submitting = state.submitting + intent.id) }
                output {
                    QuestionnaireOutput.Answered(
                        state.pending.first { it.id == intent.id },
                        intent.answer,
                        intent.context,
                        state.generations[intent.id],
                    )
                }
            }
            on<QuestionnaireIntent.Public.BeginDelivery>(guard = {
                state.matchesGeneration(intent.id, intent.expectedGeneration) &&
                    intent.id in state.submitting && intent.id !in state.preparing &&
                    state.preparing.size + state.deliveryReceipts.size < QuestionnaireDeliveryReceipt.LIMIT
            }) {
                stay { state.copy(preparing = state.preparing + intent.id) }
            }
            on<QuestionnaireIntent.Public.ReopenUndelivered>(guard = {
                state.matchesGeneration(intent.id, intent.expectedGeneration) &&
                    intent.id in state.submitting && intent.id !in state.preparing
            }) {
                stay { state.asked(state.pending.first { it.id == intent.id }, intent.generation) }
            }
            on<QuestionnaireIntent.Public.AcceptDelivery>(guard = {
                state.matchesGeneration(intent.id, intent.expectedGeneration) && intent.id in state.preparing &&
                    state.deliveryReceipts.size < QuestionnaireDeliveryReceipt.LIMIT
            }) {
                stay {
                    state.withdrawn(intent.id).copy(
                        // No generation-only absent Ask may bypass receipt ownership, even after completion.
                        publications = state.publications.record(intent.id, null),
                        deliveryReceipts = state.deliveryReceipts + (
                            intent.id to QuestionnaireDeliveryReceipt(
                                state.pending.first { it.id == intent.id },
                                intent.expectedGeneration,
                                intent.receiptToken,
                            )
                        ),
                    )
                }
                output { QuestionnaireOutput.Withdrawn(intent.id) }
            }
            on<QuestionnaireIntent.Public.CompleteDelivery>(guard = {
                state.deliveryReceipts[intent.id]?.token == intent.receiptToken
            }) {
                stay { state.copy(deliveryReceipts = state.deliveryReceipts - intent.id) }
            }
            on<QuestionnaireIntent.Public.FailDelivery>(guard = {
                state.matchesGeneration(intent.id, intent.expectedGeneration) && intent.id in state.preparing
            }) {
                stay { state.asked(state.pending.first { it.id == intent.id }, intent.generation) }
            }
            on<QuestionnaireIntent.Public.FailDelivery>(guard = {
                state.pending.none { it.id == intent.id } &&
                    state.deliveryReceipts[intent.id]?.let {
                        it.generation == intent.expectedGeneration && it.token == intent.receiptToken
                    } == true
            }) {
                stay { state.asked(state.deliveryReceipts.getValue(intent.id).questionnaire, intent.generation) }
            }
            on<QuestionnaireIntent.Public.CancelDelivery>(guard = {
                state.deliveryReceipts[intent.id]?.token == intent.receiptToken
            }) {
                stay { state.copy(deliveryReceipts = state.deliveryReceipts - intent.id) }
            }
            on<QuestionnaireIntent.Public.Withdraw>(guard = {
                (intent.expectedGeneration == null || state.matchesGeneration(intent.id, intent.expectedGeneration)) &&
                    state.pending.any { it.id == intent.id }
            }) {
                stay { state.withdrawn(intent.id) }
                output { QuestionnaireOutput.Withdrawn(intent.id) }
            }
            on<QuestionnaireIntent.Public.Withdraw>(guard = {
                intent.expectedGeneration == null && state.pending.none { it.id == intent.id }
            }) {
                stay { state.withdrawn(intent.id) }
            }
        }
        onEffectFailure { effect, _ ->
            when (effect) {
                is QuestionnaireEffect.PrepareManual -> QuestionnaireIntent.Internal.ManualPreparationFailed(
                    effect.answer.id,
                    effect.answer.admissionToken,
                )
            }
        }
    }

private val QuestionnaireIntent.Public.Answer.isSubstantiveManual: Boolean
    get() = origin == QuestionnaireAnswerOrigin.Manual && answer != Answer.Skipped

private fun QuestionnaireState.Asking.canAnswer(intent: QuestionnaireIntent.Public.Answer): Boolean =
    intent.id !in submitting && intent.id !in manualPreparations &&
        (intent.expectedGeneration == null || generations[intent.id] == intent.expectedGeneration) &&
        matchesPendingGenerations(intent.expectedPendingGenerations) && allowsOrigin(intent) &&
        pending.any { it.id == intent.id && it.accepts(intent.answer) }

private fun QuestionnaireState.Asking.matchesPreparation(prepared: QuestionnaireEffect.PrepareManual): Boolean =
    manualPreparations[prepared.answer.id] == prepared.answer.admissionToken &&
        generations[prepared.answer.id] == prepared.generation && prepared.questionnaire in pending

private fun QuestionnaireState.Asking.matchesGeneration(id: QuestionnaireId, generation: String?): Boolean =
    generation != null && pending.any { it.id == id } && generations[id] == generation

private fun QuestionnaireState.Asking.matchesPendingGenerations(expected: Map<QuestionnaireId, String>?): Boolean =
    expected == null || expected.all { (id, generation) ->
        id !in submitting && id !in manualPreparations && generations[id] == generation && pending.any { it.id == id }
    }

private fun QuestionnaireState.Asking.asked(
    questionnaire: Questionnaire,
    generation: String,
): QuestionnaireState.Asking {
    val index = pending.indexOfFirst { it.id == questionnaire.id }
    val updated = if (index < 0) {
        pending + questionnaire
    } else {
        pending.toMutableList().also { it[index] = questionnaire }
    }
    return copy(
        pending = updated,
        submitting = submitting - questionnaire.id,
        generations = generations + (questionnaire.id to generation),
        publications = publications.record(questionnaire.id, generation),
        preparing = preparing - questionnaire.id,
        deliveryReceipts = deliveryReceipts - questionnaire.id,
        manualPreparations = manualPreparations - questionnaire.id,
    )
}

private fun QuestionnaireState.Asking.withdrawn(id: QuestionnaireId): QuestionnaireState.Asking = copy(
    pending = pending.filterNot { it.id == id },
    submitting = submitting - id,
    generations = generations - id,
    publications = publications.record(id, generations[id] ?: publications.latest[id]),
    preparing = preparing - id,
    deliveryReceipts = deliveryReceipts - id,
    manualPreparations = manualPreparations - id,
)

private fun QuestionnaireState.Asking.canAsk(intent: QuestionnaireIntent.Public.Ask): Boolean =
    intent.expectedGeneration == null || matchesGeneration(intent.questionnaire.id, intent.expectedGeneration) ||
        (
            intent.isAbsentAllowed && pending.none { it.id == intent.questionnaire.id } &&
                publications.latest[intent.questionnaire.id] == intent.expectedGeneration
        )

private fun QuestionnaireState.Asking.canRestore(intent: QuestionnaireIntent.Public.Restore): Boolean =
    !publications.isInitialRestoreFinished && !publications.isInitialRestoreUnsafe &&
        !intent.questionnaire.id.value.startsWith(LIVE_QUESTION_ID_PREFIX) &&
        intent.questionnaire.id !in publications.touched && pending.none { it.id == intent.questionnaire.id }

private fun QuestionnaireState.Asking.allowsOrigin(intent: QuestionnaireIntent.Public.Answer): Boolean =
    when (intent.origin) {
        QuestionnaireAnswerOrigin.Manual -> true

        QuestionnaireAnswerOrigin.Auto ->
            intent.expectedGeneration != null && pending.any { it.id == intent.id && !autoBarrier.isBlocked(it.source) }
    }
