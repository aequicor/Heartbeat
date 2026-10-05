package io.aequicor.heartbeat.feature.checklist.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChecklistMachineTest {
    private val session = SessionRef(EngineId("test"), SessionSourceId("profile"), "session")
    private val request = RequestId("r1")
    private val choice = ChecklistField(
        "radio",
        "Result",
        ChecklistInput.SingleChoice,
        choices = listOf(ChecklistChoice("ok", "OK"), ChecklistChoice("no", "No")),
    )
    private val text = ChecklistField("text", "Notes", ChecklistInput.Text)
    private val card = Checklist("card", session, request, TurnId("t1"), "call", null, null, "Check", listOf(choice))

    @Test
    fun `loading restores without automatically completing optional radios`() {
        val restored = ChecklistJournal(listOf(card.copy(fields = listOf(choice.copy(isRequired = false)))))
        ChecklistMachineSpec.assertTransition(
            ChecklistState.Loading(),
            ChecklistIntent.Internal.Loaded(restored),
            ChecklistState.Ready(restored),
        )
    }

    @Test
    fun `creation persists card and outgoing event atomically`() {
        val before = ChecklistJournal(generations = mapOf(EventKeys.sessionSegment(session) to request))
        val after = before.copy(cards = listOf(card), outbox = listOf(card.event()), revision = 1)
        ChecklistMachineSpec.assertTransition(
            ChecklistState.Ready(before),
            ChecklistIntent.Public.Create(card),
            ChecklistState.Ready(after, savedRevision = 0),
            listOf(ChecklistEffect.Save(after)),
        )
        ChecklistMachineSpec.assertIgnored(ChecklistState.Ready(after), ChecklistIntent.Public.Create(card))
    }

    @Test
    fun `last isRequired radio completes and queues one durable event`() {
        val answer = ChecklistAnswer(selected = setOf("ok"))
        val afterCard = card.copy(
            answers = mapOf("radio" to answer),
            status = ChecklistStatus.Completed,
            delivery = ChecklistDelivery.Pending,
            revision = 3,
        )
        val after = ChecklistJournal(listOf(afterCard), listOf(afterCard.event()), revision = 1)
        ChecklistMachineSpec.assertTransition(
            ChecklistState.Ready(ChecklistJournal(listOf(card))),
            ChecklistIntent.Public.Answer("card", "radio", answer),
            ChecklistState.Ready(after, savedRevision = 0),
            listOf(ChecklistEffect.Save(after)),
        )
        ChecklistMachineSpec.assertIgnored(ChecklistState.Ready(after), ChecklistIntent.Public.Complete("card"))
    }

    @Test
    fun `mixed fields wait for explicit completion and reject blank isRequired text`() {
        val mixed = card.copy(fields = listOf(choice, text))
        assertFalse(mixed.isAutomatic)
        assertFalse(mixed.isCompletionAllowed)
        val filled = mixed.copy(
            answers = mapOf("radio" to ChecklistAnswer(setOf("ok")), "text" to ChecklistAnswer(text = "tested")),
        )
        assertTrue(filled.isCompletionAllowed)
        assertFalse(
            filled.copy(answers = filled.answers + ("text" to ChecklistAnswer(text = "  "))).isCompletionAllowed,
        )
        val saved = filled.copy(status = ChecklistStatus.Completed, delivery = ChecklistDelivery.Pending, revision = 2)
        val after = ChecklistJournal(listOf(saved), listOf(saved.event()), revision = 1)
        ChecklistMachineSpec.assertTransition(
            ChecklistState.Ready(ChecklistJournal(listOf(filled))),
            ChecklistIntent.Public.Complete("card"),
            ChecklistState.Ready(after, savedRevision = 0),
            listOf(ChecklistEffect.Save(after)),
        )
    }

    @Test
    fun `multiple selections enforce bounds and known ids`() {
        val multi = choice.copy(type = ChecklistInput.MultiChoice, min = 2, max = 2)
        assertFalse(multi.accepts(ChecklistAnswer(setOf("ok"))))
        assertTrue(multi.accepts(ChecklistAnswer(setOf("ok", "no"))))
        assertFalse(multi.accepts(ChecklistAnswer(setOf("ok", "unknown"))))
        assertFalse(multi.acceptsDraft(ChecklistAnswer(setOf("ok"), text = "wrong type")))
        assertTrue(multi.copy(isRequired = false).accepts(ChecklistAnswer()))
    }

    @Test
    fun `new request supersedes readiness but preserves continuation cards`() {
        val ready = card.copy(mode = ChecklistCompletionMode.MarkSessionReady)
        val old = ChecklistJournal(listOf(ready))
        val next = RequestId("r2")
        val outdated = ready.copy(status = ChecklistStatus.Superseded, revision = 2)
        val after = old.copy(
            cards = listOf(outdated),
            outbox = listOf(outdated.event()),
            revision = 1,
            generations = mapOf(EventKeys.sessionSegment(session) to next),
            generationRevisions = mapOf(EventKeys.sessionSegment(session) to 0),
        )
        ChecklistMachineSpec.assertTransition(
            ChecklistState.Ready(old),
            ChecklistIntent.Internal.Started(session, next),
            ChecklistState.Ready(after, savedRevision = 0),
            listOf(ChecklistEffect.Save(after)),
        )
        ChecklistMachineSpec.assertIgnored(ChecklistState.Ready(after), ChecklistIntent.Public.Complete("card"))
        val preserved = ChecklistMachineSpec.resolve(
            ChecklistState.Ready(ChecklistJournal(listOf(card))),
            ChecklistIntent.Internal.Started(session, next),
        )!!.to as ChecklistState.Ready
        assertEquals(ChecklistStatus.Open, preserved.journal.cards.single().status)
    }

    @Test
    fun `failure preserves unsaved draft and retry saves the same snapshot`() {
        val journal = ChecklistJournal(listOf(card), revision = 3)
        val before = ChecklistState.Ready(journal, savedRevision = 2)
        val failed = before.copy(hasFailed = true)
        ChecklistMachineSpec.assertTransition(before, ChecklistIntent.Internal.Failed, failed)
        ChecklistMachineSpec.assertTransition(
            failed,
            ChecklistIntent.Public.Retry,
            failed,
            listOf(ChecklistEffect.Save(journal)),
        )
    }

    @Test
    fun `acknowledgement removes only its event and old delivery cannot settle a retry`() {
        val complete = card.copy(status = ChecklistStatus.Completed, delivery = ChecklistDelivery.Pending, attempt = 1)
        val before = ChecklistJournal(listOf(complete), listOf(complete.event()))
        val after = before.copy(outbox = emptyList(), revision = 1)
        ChecklistMachineSpec.assertTransition(
            ChecklistState.Ready(before),
            ChecklistIntent.Internal.Acknowledged(complete.event().eventId),
            ChecklistState.Ready(after, savedRevision = 0),
            listOf(ChecklistEffect.Save(after)),
        )
        ChecklistMachineSpec.assertIgnored(
            ChecklistState.Ready(before),
            ChecklistIntent.Internal.Delivered("card", 0, true),
        )
    }

    @Test
    fun `late generation replay and parallel creation key cannot create duplicate cards`() {
        val journal = ChecklistJournal(
            listOf(card),
            generations = mapOf(EventKeys.sessionSegment(session) to request),
            generationRevisions = mapOf(EventKeys.sessionSegment(session) to 3),
        )
        ChecklistMachineSpec.assertIgnored(
            ChecklistState.Ready(journal),
            ChecklistIntent.Internal.Started(session, RequestId("older"), 2),
        )
        ChecklistMachineSpec.assertIgnored(
            ChecklistState.Ready(journal),
            ChecklistIntent.Public.Create(card.copy(id = "another", creationKey = card.creationKey)),
        )
    }

    @Test
    fun `late save acknowledgement cannot hide a newer failed write`() {
        val failed = ChecklistState.Ready(
            ChecklistJournal(listOf(card), revision = 3),
            savedRevision = 1,
            hasFailed = true,
        )
        ChecklistMachineSpec.assertTransition(failed, ChecklistIntent.Internal.Saved(2), failed.copy(savedRevision = 2))
    }

    @Test
    fun `journal and compact events survive serialization`() {
        val journal = ChecklistJournal(listOf(card), listOf(card.event()))
        assertEquals(journal, Json.decodeFromString<ChecklistJournal>(Json.encodeToString(journal)))
        assertEquals(
            "custom.checklist.card.completed",
            ChecklistEvents.changed("card", ChecklistStatus.Completed).value,
        )
    }
}
