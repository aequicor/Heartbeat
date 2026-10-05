package io.aequicor.heartbeat.feature.checklist.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.machineSpec
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys

/**
 * | State | Input | Result |
 * |---|---|---|
 * | Loading | Start / Retry | Load, remain Loading |
 * | Loading | Loaded / Failed | Ready / retriable Loading |
 * | Ready | Create / Answer / Complete | Validate, update snapshot, Save |
 * | Ready | Started | Record generation, supersede old readiness cards, Save |
 * | Ready | Acknowledged / Delivered | Settle event or wake, Save |
 * | Ready | Saved / Failed / Retry | Confirm persistence / keep draft / retry Save |
 *
 * Every Ready transition stays in place, so saving one card never cancels another card's effects.
 * Only actual user answers can trigger automatic completion; loading never does. Unknown, stale and duplicate
 * commands are ignored. Published events are taken from durable storage, never from optimistic machine state.
 */
public val ChecklistMachineSpec:
    MachineSpec<ChecklistState, ChecklistIntent, ChecklistEffect, ChecklistOutput> =
    machineSpec(ChecklistMachineKey, ChecklistState.Loading()) {
        state<ChecklistState.Loading> {
            on<ChecklistIntent.Internal.Start> { effect { ChecklistEffect.Load } }
            on<ChecklistIntent.Public.Retry> { effect { ChecklistEffect.Load } }
            on<ChecklistIntent.Internal.Loaded> {
                goto<ChecklistState.Ready> { ChecklistState.Ready(intent.journal) }
            }
            on<ChecklistIntent.Internal.Failed> { stay { ChecklistState.Loading(hasFailed = true) } }
        }
        state<ChecklistState.Ready> {
            on<ChecklistIntent.Public.Create>(guard = {
                state.journal.cards.none {
                    it.id == intent.card.id ||
                        (
                            it.session == intent.card.session && it.request == intent.card.request &&
                                it.creationKey == intent.card.creationKey
                        )
                } &&
                    state.journal.generations[EventKeys.sessionSegment(intent.card.session)] == intent.card.request
            }) {
                stay { state.changed(state.journal.created(intent.card)) }
                effect { ChecklistEffect.Save(state.journal.created(intent.card)) }
            }
            on<ChecklistIntent.Public.Answer>(guard = {
                state.journal.cards.any { card ->
                    card.id == intent.id && card.status == ChecklistStatus.Open &&
                        card.fields.any { it.id == intent.field && it.acceptsDraft(intent.answer) }
                }
            }) {
                stay { state.changed(state.journal.answered(intent)) }
                effect { ChecklistEffect.Save(state.journal.answered(intent)) }
            }
            on<ChecklistIntent.Public.Complete>(guard = {
                state.journal.cards.any { it.id == intent.id && it.isCompletionAllowed }
            }) {
                stay { state.changed(state.journal.edited(intent.id) { completed() }) }
                effect { ChecklistEffect.Save(state.journal.edited(intent.id) { completed() }) }
            }
            on<ChecklistIntent.Public.RetryDelivery>(guard = {
                state.journal.cards.any { it.id == intent.id && it.delivery == ChecklistDelivery.Failed }
            }) {
                stay { state.changed(state.journal.retried(intent.id)) }
                effect { ChecklistEffect.Save(state.journal.retried(intent.id)) }
            }
            on<ChecklistIntent.Internal.Started>(guard = {
                intent.revision > (state.journal.generationRevisions[EventKeys.sessionSegment(intent.session)] ?: -1)
            }) {
                stay { state.changed(state.journal.started(intent)) }
                effect { ChecklistEffect.Save(state.journal.started(intent)) }
            }
            on<ChecklistIntent.Internal.Acknowledged>(guard = {
                state.journal.outbox.any { it.eventId == intent.eventId }
            }) {
                stay { state.changed(state.journal.acknowledged(intent.eventId)) }
                effect { ChecklistEffect.Save(state.journal.acknowledged(intent.eventId)) }
            }
            on<ChecklistIntent.Internal.Delivered>(guard = {
                state.journal.cards.any {
                    it.id == intent.id && it.attempt == intent.attempt && it.delivery == ChecklistDelivery.Pending
                }
            }) {
                stay { state.changed(state.journal.delivered(intent)) }
                effect { ChecklistEffect.Save(state.journal.delivered(intent)) }
            }
            on<ChecklistIntent.Internal.Saved> {
                stay {
                    state.copy(
                        savedRevision = maxOf(state.savedRevision, intent.revision),
                        hasFailed = state.hasFailed && intent.revision < state.journal.revision,
                    )
                }
            }
            on<ChecklistIntent.Internal.Failed> { stay { state.copy(hasFailed = true) } }
            on<ChecklistIntent.Public.Retry> { effect { ChecklistEffect.Save(state.journal) } }
        }
        onEffectFailure { _, _ -> ChecklistIntent.Internal.Failed }
    }

private fun ChecklistState.Ready.changed(next: ChecklistJournal) = copy(journal = next, hasFailed = false)

private fun ChecklistJournal.created(card: Checklist) = copy(
    cards = cards + card,
    outbox = outbox + card.event(),
    revision = revision + 1,
)

private fun ChecklistJournal.answered(intent: ChecklistIntent.Public.Answer) = edited(intent.id) {
    val updated = copy(answers = answers + (intent.field to intent.answer), revision = revision + 1)
    if (updated.isAutomatic && updated.isCompletionAllowed) updated.completed() else updated
}

private fun Checklist.completed() = copy(
    status = ChecklistStatus.Completed,
    delivery = if (mode == ChecklistCompletionMode.ResumeSession) ChecklistDelivery.Pending else ChecklistDelivery.None,
    revision = revision + 1,
)

private fun ChecklistJournal.edited(id: String, change: Checklist.() -> Checklist): ChecklistJournal {
    val old = cards.first { it.id == id }
    val next = old.change()
    val event = if (next.status != old.status || next.attempt != old.attempt) listOf(next.event()) else emptyList()
    return copy(cards = cards.map { if (it.id == id) next else it }, outbox = outbox + event, revision = revision + 1)
}

private fun ChecklistJournal.retried(id: String) = edited(id) {
    copy(delivery = ChecklistDelivery.Pending, attempt = attempt + 1, revision = revision + 1)
}

private fun ChecklistJournal.acknowledged(id: String) =
    copy(outbox = outbox.filterNot { it.eventId == id }, revision = revision + 1)

private fun ChecklistJournal.delivered(intent: ChecklistIntent.Internal.Delivered) = edited(intent.id) {
    copy(delivery = if (intent.isSuccessful) ChecklistDelivery.Delivered else ChecklistDelivery.Failed)
}

private fun ChecklistJournal.started(intent: ChecklistIntent.Internal.Started): ChecklistJournal {
    val obsolete = cards.filter {
        it.session == intent.session && it.request != intent.request &&
            it.mode == ChecklistCompletionMode.MarkSessionReady && it.status != ChecklistStatus.Superseded
    }.map { it.copy(status = ChecklistStatus.Superseded, revision = it.revision + 1) }
    return copy(
        cards = cards.map { old -> obsolete.firstOrNull { it.id == old.id } ?: old },
        outbox = outbox + obsolete.map(Checklist::event),
        generations = generations + (EventKeys.sessionSegment(intent.session) to intent.request),
        generationRevisions = generationRevisions + (EventKeys.sessionSegment(intent.session) to intent.revision),
        revision = revision + 1,
    )
}
