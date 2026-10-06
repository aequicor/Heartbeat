package io.aequicor.heartbeat.feature.organicai.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.accepts

/**
 * One change of one organism and what the host must do for it. Only a cell that explicitly requested a result
 * wait wakes ([drive]); other letters wait in inboxes. A kill may also wake its plaintiff ([rouse]);
 * [driveCells] covers waiting parties of a dispute and cells explicitly resumed by the user.
 */
internal data class Step(
    val organism: Organism,
    val isDurable: Boolean = true,
    val isResolving: Boolean = false,
    val drive: CellId? = null,
    val rouse: CellId? = null,
    val judge: CaseId? = null,
    val release: List<CellId> = emptyList(),
    val releaseMode: ReleaseMode = ReleaseMode.Retire,
    val response: Response? = null,
    val isFinished: Boolean = false,
    val driveCells: List<CellId> = emptyList(),
)

/** A user decision to deliver to [cell]. */
internal data class Response(val cell: CellId, val decision: PermissionDecision)

/** What [intent] does to the organisms, or null when it does not apply (unknown organism, stale or refused). */
internal fun OrganicAiState.Living.evolve(intent: OrganismIntent): Step? {
    val organism = organisms[intent.organism]
    if (intent is OrganicAiIntent.Public.Conceive) return if (organism == null) intent.conception.conceive() else null
    return organism?.evolve(intent)?.let { step ->
        if (step.isDurable) step.copy(organism = step.organism.copy(version = organism.version + 1)) else step
    }
}

/** [step] applied to the organisms. */
internal fun OrganicAiState.Living.apply(step: Step): OrganicAiState.Living =
    copy(organisms = organisms + (step.organism.id to step.organism))

/**
 * The organism as it continues after a restart: every working cell starts a fresh turn of the same work, a
 * recovery turn when its session exists. Observed turns and permission requests are gone.
 */
internal fun Organism.awakened(): Organism {
    if (!isDeveloping) return this
    val cells = cells.map { cell ->
        when (val phase = cell.phase) {
            is CellPhase.Working -> recover(cell, phase.work)
            CellPhase.Resting -> if (cell.isAwaitingResults && cell.unreadLetters().isNotEmpty()) wake(cell) else cell
            else -> cell
        }
    }
    return copy(cells = cells, version = version + 1)
}

internal fun Conception.conceive(): Step {
    val zygote = Cell(CellId.ZYGOTE, "zygote", parent = null, task = goal, phase = CellPhase.Resting)
    val organism = Organism(
        id, goal, target, immunityTarget, workspace, trust, limits, listOf(zygote),
        version = 1,
        attachments = attachments,
    )
    val started = organism.updated(CellId.ZYGOTE) { organism.begin(it, Work.Genesis) }
    return if (target == null) {
        Step(started, isResolving = true)
    } else {
        Step(started, drive = CellId.ZYGOTE)
    }
}

private fun Organism.evolve(intent: OrganismIntent): Step? = when (intent) {
    is OrganicAiIntent.Public.Conceive -> null
    is OrganicAiIntent.Public.Abort -> abort()
    is OrganicAiIntent.Public.Resume -> resume()
    is OrganicAiIntent.Public.FollowUp -> followUp(intent)
    is OrganicAiIntent.Public.Decide -> decide(intent.cell, intent.decision)
    is OrganicAiIntent.Internal.Targeted -> targeted(intent.target)
    is OrganicAiIntent.Internal.Unresolved -> unresolved()
    is OrganicAiIntent.Internal.Divide -> divide(intent)
    is CaseIntent -> immunity(intent)
    is TurnIntent -> working(intent.cell, intent.request)?.let { follow(it, intent) }
}

/** Cases before the immune system: filing, the judge's session and the ruling. */
private fun Organism.immunity(intent: CaseIntent): Step? = when (intent) {
    is OrganicAiIntent.Internal.Complain -> file(
        intent.complaint,
        complaintRefusal(intent.complaint.plaintiff, intent.complaint.accused),
    )

    is OrganicAiIntent.Internal.Dispute -> file(
        intent.dispute,
        disputeRefusal(intent.dispute.asker, intent.dispute.parties),
    )

    is OrganicAiIntent.Internal.JudgeConvened -> trials.firstOrNull { it.case.id == intent.case && it.ruling == null }
        ?.let { trial -> Step(copy(trials = trials.replaced(trial.copy(judge = intent.session)))) }

    is OrganicAiIntent.Internal.Ruled -> cases.firstOrNull { it.id == intent.case }
        ?.takeIf { isDeveloping }
        ?.let { rule(it, intent.ruling) }
}

/** Driver feedback for the turn [cell] is working on. */
private fun Organism.follow(cell: Cell, intent: TurnIntent): Step? {
    val phase = cell.phase as CellPhase.Working
    return when (intent) {
        is OrganicAiIntent.Internal.SessionBound -> if (cell.session == null) {
            Step(updated(cell.id) { it.copy(session = intent.session) })
        } else {
            null
        }

        is OrganicAiIntent.Internal.TurnAccepted -> observe(cell, phase.copy(turn = intent.turn))

        is OrganicAiIntent.Internal.PermissionsChanged -> observe(cell, phase.copy(awaiting = intent.pending))

        is OrganicAiIntent.Internal.TurnSettled -> settle(cell, intent.settlement)

        is OrganicAiIntent.Internal.ReceiveLetters -> if (intent.until in 0..cell.inbox.size) {
            Step(
                updated(cell.id) {
                    it.copy(receivedLetters = maxOf(it.receivedLetters, intent.until), isAwaitingResults = false)
                },
            )
        } else {
            null
        }

        is OrganicAiIntent.Internal.AwaitResults -> if (cell.unreadLetters().isNotEmpty() || isWaiting(cell.id)) {
            Step(updated(cell.id) { it.copy(isAwaitingResults = true) })
        } else {
            null
        }
    }
}

/** The living [id] while its turn of [request] is under way. */
private fun Organism.working(id: CellId, request: RequestId): Cell? =
    cell(id)?.takeIf { isDeveloping && (it.phase as? CellPhase.Working)?.request == request }

/** A change of observed, unsaved facts of a working turn. */
private fun Organism.observe(cell: Cell, phase: CellPhase.Working): Step =
    Step(updated(cell.id) { it.copy(phase = phase) }, isDurable = false)

private fun Organism.decide(id: CellId, decision: PermissionDecision): Step? {
    val phase = cell(id)?.phase as? CellPhase.Working ?: return null
    if (!isDeveloping || phase.awaiting.none { it.accepts(decision) }) return null
    return Step(this, isDurable = false, response = Response(id, decision))
}

private fun Organism.targeted(target: EngineTarget): Step? {
    if (!isDeveloping || this.target != null) return null
    val zygote = zygote
    return Step(copy(target = target), drive = zygote.id.takeIf { zygote.phase is CellPhase.Working })
}

private fun Organism.unresolved(): Step? {
    val zygote = zygote
    val phase = zygote.phase as? CellPhase.Working ?: return null
    if (!isDeveloping || target != null) return null
    return Step(updated(zygote.id) { it.copy(phase = CellPhase.Stalled(Breakdown.NoModel, phase.work)) })
}

private fun Organism.followUp(intent: OrganicAiIntent.Public.FollowUp): Step? {
    if (status !is OrganismStatus.Completed || zygote.phase !is CellPhase.Completed) return null
    if ((intent.text.isBlank() && intent.attachments.isEmpty()) || intent.text.length > OrganismBounds.MAX_GOAL) {
        return null
    }
    val continued = updated(zygote.id) {
        begin(it.copy(isAwaitingResults = false), Work.FollowUp(intent.text, intent.attachments))
    }.copy(status = OrganismStatus.Developing)
    return if (target == null) Step(continued, isResolving = true) else Step(continued, drive = zygote.id)
}

private fun Organism.resume(): Step? {
    val zygote = zygote
    if (status is OrganismStatus.Completed && zygote.phase is CellPhase.Completed) {
        val restarted = updated(zygote.id) { recover(it.copy(isAwaitingResults = false), Work.Genesis) }
            .copy(status = OrganismStatus.Developing)
        return if (target == null) Step(restarted, isResolving = true) else Step(restarted, drive = zygote.id)
    }
    if (!isDeveloping) return null
    val resumed = cells.filter { it.phase == CellPhase.Resting && !it.isAwaitingResults }.map { it.id }
    if (zygote.phase == CellPhase.Resting && resumed.isNotEmpty()) {
        return Step(
            copy(cells = cells.map { if (it.id in resumed) begin(it, Work.CheckInbox) else it }),
            driveCells = resumed,
        )
    }
    val phase = zygote.phase as? CellPhase.Stalled ?: return null
    val restarted = updated(zygote.id) { recover(it, phase.work) }
    return if (target == null) Step(restarted, isResolving = true) else Step(restarted, drive = zygote.id)
}

private fun Organism.abort(): Step? {
    if (!isDeveloping) return null
    val ended = cells.filter(Cell::isAlive).map(Cell::id)
    val aborted = copy(
        cells = cells.map { if (it.isAlive) it.copy(phase = CellPhase.Dead(DeathCause.Aborted)) else it },
        cases = emptyList(),
        status = OrganismStatus.Aborted,
    )
    return Step(aborted, release = ended, releaseMode = ReleaseMode.Lyse, isFinished = true)
}

private fun Organism.divide(intent: OrganicAiIntent.Internal.Divide): Step? {
    if (divisionRefusal(intent.parent) != null || intent.child != nextCellId()) return null
    val child = Cell(intent.child, intent.name, intent.parent, intent.task, CellPhase.Resting)
    return Step(copy(cells = cells + begin(child, Work.Genesis)), drive = child.id)
}

private fun Organism.file(case: ImmuneCase, refusal: Refusal?): Step? {
    if (refusal != null || case.id != nextCaseId()) return null
    return Step(copy(cases = cases + case, trials = trials + Trial(case), casesFiled = casesFiled + 1), judge = case.id)
}

private fun Organism.settle(cell: Cell, settlement: Settlement): Step = when (settlement) {
    is Settlement.Answered -> answered(cell, settlement.text)
    is Settlement.Broke -> broke(cell, settlement.breakdown)
}

/** Only an explicit wait starts another turn. Unread results must be requested before the answer is final. */
private fun Organism.answered(cell: Cell, text: String): Step = when {
    cell.isAwaitingResults && cell.unreadLetters().isNotEmpty() -> Step(updated(cell.id) { wake(it) }, drive = cell.id)

    isWaiting(cell.id) || cell.unreadLetters().isNotEmpty() ->
        Step(updated(cell.id) { it.copy(phase = CellPhase.Resting) })

    else -> {
        val completed = updated(cell.id) { it.copy(phase = CellPhase.Completed(text)) }
        if (cell.isZygote) {
            // Complaints still open against ended cells are moot; their judges' rulings are ignored.
            Step(
                completed.copy(status = OrganismStatus.Completed(text), cases = emptyList()),
                release = listOf(cell.id),
                isFinished = true,
            )
        } else {
            val (posted, woken) = completed.post(cell.parent, Letter.ChildFinished(cell.id, cell.name, text), true)
            Step(posted, drive = woken, release = listOf(cell.id))
        }
    }
}

/** A broken zygote stalls until resumed; any other cell dies with its descendants and its parent is told. */
private fun Organism.broke(cell: Cell, breakdown: Breakdown): Step {
    val phase = cell.phase as CellPhase.Working
    if (cell.isZygote) return Step(updated(cell.id) { it.copy(phase = CellPhase.Stalled(breakdown, phase.work)) })
    val cause = DeathCause.Failed(breakdown)
    val (excised, ended) = excise(cell.id, cause)
    val (posted, woken) = excised.post(cell.parent, Letter.ChildDied(cell.id, cell.name, cause), wakes = true)
    return Step(posted, drive = woken, release = ended, releaseMode = ReleaseMode.Lyse)
}

private fun Organism.rule(case: ImmuneCase, ruling: Ruling): Step {
    val trial = trials.firstOrNull { it.case.id == case.id }
    val closed = copy(cases = cases - case, trials = trial?.let { trials.replaced(it.copy(ruling = ruling)) } ?: trials)
    return when (case) {
        is ImmuneCase.Complaint -> closed.sentence(case, ruling)
        is ImmuneCase.Dispute -> closed.answer(case, ruling)
    }
}

/**
 * Executes a kill on a living non-zygote accused: it is lysed with its descendants and its parent is told. The
 * plaintiff, which waits for its verdict, wakes with it; the verdict is queued before the parent's letter, so a parent
 * that filed reads both in one turn.
 */
private fun Organism.sentence(case: ImmuneCase.Complaint, ruling: Ruling): Step {
    val name = cell(case.accused)?.name.orEmpty()
    val accused = cell(case.accused)?.takeIf { ruling is Ruling.Kill && it.isAlive && !it.isZygote }
    if (accused == null) {
        val outcome = when (ruling) {
            is Ruling.Kill -> VerdictOutcome.Moot
            is Ruling.Spare -> VerdictOutcome.Spared
            is Ruling.Answer, is Ruling.None -> VerdictOutcome.Undecided
        }
        val verdict = Letter.Verdict(case.id, case.accused, name, outcome, ruling.reason)
        val (posted, woken) = post(case.plaintiff, verdict, wakes = true)
        return Step(posted, drive = woken)
    }
    val cause = DeathCause.Lysed(case.id, ruling.reason)
    val (excised, ended) = excise(accused.id, cause)
    val verdict = Letter.Verdict(case.id, accused.id, name, VerdictOutcome.Killed, ruling.reason)
    val (told, _) = excised.post(case.plaintiff, verdict, wakes = false)
    val (posted, woken) = told.post(accused.parent, Letter.ChildDied(accused.id, name, cause), wakes = true)
    val (roused, plaintiff) = posted.rouse(case.plaintiff)
    return Step(roused, drive = woken, rouse = plaintiff, release = ended, releaseMode = ReleaseMode.Lyse)
}

/** Wakes [id] when it still rests with letters, such as a plaintiff other than the parent of the killed cell. */
private fun Organism.rouse(id: CellId): Pair<Organism, CellId?> {
    cell(id)?.takeIf { it.phase == CellPhase.Resting && it.isAwaitingResults && it.unreadLetters().isNotEmpty() }
        ?: return this to null
    return updated(id) { wake(it) } to id
}

/** All recipients who explicitly requested a result wait may wake, including the other parties. */
private fun Organism.answer(case: ImmuneCase.Dispute, ruling: Ruling): Step {
    val letter = Letter.DisputeResolved(case.id, case.question, (ruling as? Ruling.Answer)?.text, ruling.reason)
    val parties = mutableListOf<CellId>()
    val told = case.parties.fold(this) { organism, party ->
        val (posted, woken) = organism.post(party, letter, wakes = true)
        woken?.let(parties::add)
        posted
    }
    val (posted, woken) = told.post(case.asker, letter, wakes = true)
    return Step(posted, drive = woken, driveCells = parties)
}

/**
 * Appends [letter] without delivering its text. A resting cell wakes only when it explicitly requested a wait
 * and the letter [wakes] it; a working cell always keeps its current turn. Returns the organism and the woken cell.
 */
private fun Organism.post(to: CellId?, letter: Letter, wakes: Boolean): Pair<Organism, CellId?> {
    val target = to?.let(::cell) ?: return this to null
    return when (target.phase) {
        is CellPhase.Completed, is CellPhase.Dead -> this to null

        CellPhase.Resting -> if (wakes && target.isAwaitingResults) {
            updated(target.id) { wake(it.copy(inbox = it.inbox + letter)) } to target.id
        } else {
            updated(target.id) { it.copy(inbox = it.inbox + letter) } to null
        }

        is CellPhase.Working, is CellPhase.Stalled -> updated(target.id) { it.copy(inbox = it.inbox + letter) } to null
    }
}

/** Ends [root] with [cause] and its living descendants as its orphans; returns the cells that were alive. */
private fun Organism.excise(root: CellId, cause: DeathCause): Pair<Organism, List<CellId>> {
    val ended = subtree(root).filter(Cell::isAlive).map(Cell::id)
    val excised = copy(
        cells = cells.map { cell ->
            when (cell.id) {
                root -> cell.copy(phase = CellPhase.Dead(cause))
                in ended -> cell.copy(phase = CellPhase.Dead(DeathCause.Orphaned(root)))
                else -> cell
            }
        },
    )
    return excised to ended
}

/** A requested wake carries only a reminder; the tool reads the durable inbox separately. */
private fun Organism.wake(cell: Cell): Cell = begin(cell.copy(isAwaitingResults = false), Work.CheckInbox)

/** Migrates old persisted letters turns before recovery; their result text can only be read through the tool. */
private fun Organism.recover(cell: Cell, work: Work): Cell {
    val legacy = work as? Work.Letters
    val restored = if (legacy == null) cell else cell.copy(inbox = legacy.letters + cell.inbox)
    return begin(restored, if (legacy == null) work else Work.CheckInbox, isRecovery = cell.session != null)
}

/** [cell] starting its next turn on [work] with a fresh request id. */
private fun Organism.begin(cell: Cell, work: Work, isRecovery: Boolean = false): Cell {
    val turn = cell.turns + 1
    return cell.copy(phase = CellPhase.Working(requestFor(cell.id, turn), work, isRecovery), turns = turn)
}

private fun List<Trial>.replaced(trial: Trial): List<Trial> = map { if (it.case.id == trial.case.id) trial else it }

private fun Organism.updated(id: CellId, change: (Cell) -> Cell): Organism =
    copy(cells = cells.map { if (it.id == id) change(it) else it })
