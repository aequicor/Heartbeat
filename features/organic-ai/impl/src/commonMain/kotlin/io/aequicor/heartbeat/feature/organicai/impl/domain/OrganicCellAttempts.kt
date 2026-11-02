package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.organicai.api.Cell
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.Work

/** Durable attempt selection; recovery observes the original request instead of claiming its native work anew. */
internal class OrganicCellAttempts(private val journal: OrganicNativeJournal) {
    suspend fun select(organism: Organism, cell: Cell, route: CellRoute): OrganicNativeExecution {
        val incarnation = checkNotNull(organism.incarnation) { "Legacy execution has no proven incarnation" }
        val phase = cell.phase as CellPhase.Working
        val address = organism.executor(OrganicExecutorAddress.Kind.Cell, cell.id.value).id
        val known = journal.attempts(organism.id, incarnation).filter { it.claim.address == address }
        val previous = known.singleOrNull { !it.isQuiescent }
            ?: known.singleOrNull { it.claim.request == phase.request }
            ?: known.lastOrNull().takeIf { phase.isRecovery }
        if (previous != null) {
            check(previous.claim.request == phase.request || phase.isRecovery) { "Earlier request remains unresolved" }
            return OrganicNativeExecution(journal, organism.id, incarnation, previous, isRestored = true)
        }
        check(!phase.isRecovery || journal.isTracked(organism.id, incarnation)) {
            "Recovery has no durable native claim"
        }
        val inputs = when (val work = phase.work) {
            Work.Genesis -> organism.attachments
            is Work.FollowUp -> work.attachments
            Work.CheckInbox, is Work.Letters -> emptyList()
        }
        val claim = OrganicNativeClaim(
            token = phase.request.value,
            address = address,
            execution = route.execution,
            target = route.target,
            workspace = route.workspace,
            request = phase.request,
            prompt = turnPrompt(organism, cell),
            inputs = inputs,
        )
        val receipt = journal.reserve(organism.id, incarnation, claim)
        return OrganicNativeExecution(journal, organism.id, incarnation, receipt, isRestored = false)
    }
}

/** One observer's immutable claim; callbacks enrich only that claim, including after its owner has ended. */
internal class OrganicNativeExecution(
    private val journal: OrganicNativeJournal,
    private val organism: OrganismId,
    private val incarnation: String,
    val initial: OrganicNativeAttempt,
    val isRestored: Boolean,
) {
    val claim: OrganicNativeClaim get() = initial.claim

    suspend fun opening() {
        check(!isRestored) { "A restored opening must never create another native context" }
        update(OrganicNativeStage.Reserved) { it.copy(stage = OrganicNativeStage.Opening) }
    }

    suspend fun opened(session: SessionRef) {
        val current = current()
        if (current.stage == OrganicNativeStage.Opening) {
            advance(current, current.copy(stage = OrganicNativeStage.Opened, session = session))
        } else {
            check(current.session == session && current.stage != OrganicNativeStage.Reserved) {
                "Native opening acknowledgement changed its session"
            }
        }
    }

    /** Persists the normalized original request before hooks or native send; only the first caller may enter. */
    suspend fun prepare(prompt: PromptRequest) {
        val parts = listOf(ContentPart.Text(claim.prompt)) + claim.inputs.map {
            if (it.mediaType.startsWith("image/")) ContentPart.Image(it) else ContentPart.Resource(it)
        }
        check(prompt.id == claim.request && prompt.parts == parts && prompt.compaction == null) {
            "Native payload differs from its immutable claim"
        }
        update(OrganicNativeStage.Opened) { it.copy(stage = OrganicNativeStage.Entered, prepared = prompt) }
        if (claim.execution == null) {
            // The human-capable zygote has no executor gate; this host still consumes its own send exactly once.
            update(OrganicNativeStage.Entered) { it.copy(stage = OrganicNativeStage.Sending) }
        }
    }

    /** An authoritative idle handle before send proves this attempt never began a native turn. */
    suspend fun idle() {
        update(OrganicNativeStage.Opened) {
            it.copy(stage = OrganicNativeStage.Settled, end = OrganicNativeEnd.NativeIdle)
        }
    }

    suspend fun accepted(session: SessionRef, turn: TurnId) {
        val current = current()
        check(current.session == session) { "Native acknowledgement belongs to another session" }
        if (current.stage == OrganicNativeStage.Accepted || current.isQuiescent) {
            check(current.turn == turn) { "Native acknowledgement changed its accepted turn" }
            return
        }
        check(current.stage == OrganicNativeStage.Sending || current.stage == OrganicNativeStage.Entered) {
            "Native acknowledgement has no entered request"
        }
        advance(current, current.copy(stage = OrganicNativeStage.Accepted, turn = turn))
    }

    /** A resumed facade may localize a native turn differently; exact request and target prove that alias. */
    suspend fun observed(session: SessionRef, turn: Turn) {
        check(turn.request == claim.request && turn.target == claim.target) { "Recovered native request differs" }
        val current = current()
        check(current.session == session) { "Recovered native session differs" }
        if (current.stage == OrganicNativeStage.Accepted) return
        accepted(session, turn.id)
    }

    /** Unknown is deliberately retained; a logical answer is not evidence that native work stopped. */
    suspend fun settled(outcome: TurnOutcome, answer: String?) {
        if (outcome == TurnOutcome.Unknown) return
        val current = current()
        if (current.isQuiescent) {
            check(current.outcome == outcome && current.answer == answer) { "Native terminal result changed" }
            return
        }
        check(current.stage == OrganicNativeStage.Accepted) { "Terminal result has no accepted native request" }
        advance(
            current,
            current.copy(
                stage = OrganicNativeStage.Settled,
                outcome = outcome,
                end = OrganicNativeEnd.NativeTerminal,
                answer = answer,
            ),
        )
    }

    /** Called only after the owned handle authoritatively reported Ready following Stop. */
    suspend fun idleAfterStop(session: SessionRef, last: Turn?) {
        val current = current()
        check(current.session == session) { "Stop evidence belongs to another session" }
        if (current.isQuiescent) return
        if (current.stage == OrganicNativeStage.Opened) {
            idle()
        } else if (last?.request == claim.request && last.target == claim.target) {
            val outcome = last.outcome
            if (outcome != null && outcome != TurnOutcome.Unknown) {
                observed(session, last)
                settled(outcome, null)
            }
        }
    }

    private suspend fun current(): OrganicNativeAttempt =
        journal.attempts(organism, incarnation).single { it.claim.token == claim.token }

    private suspend fun update(stage: OrganicNativeStage, change: (OrganicNativeAttempt) -> OrganicNativeAttempt) {
        val current = current()
        check(current.stage == stage) { "Native request boundary was already crossed" }
        advance(current, change(current))
    }

    private suspend fun advance(current: OrganicNativeAttempt, next: OrganicNativeAttempt) {
        journal.advance(organism, incarnation, current, next.copy(revision = current.revision + 1))
    }
}
