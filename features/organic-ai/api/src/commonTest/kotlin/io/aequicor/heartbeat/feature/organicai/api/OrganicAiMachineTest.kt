package io.aequicor.heartbeat.feature.organicai.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.core.statemachine.toMermaid
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrganicAiMachineTest {
    private val spec = OrganicAiMachineSpec

    private fun living(vararg organisms: Organism) = OrganicAiState.Living(organisms.associateBy { it.id })

    private fun OrganicAiState.organism(): Organism = (this as OrganicAiState.Living).organisms.getValue(ORGANISM)

    private fun settled(cell: CellId, turn: Int = 1, text: String = "answer") =
        OrganicAiIntent.Internal.TurnSettled(ORGANISM, cell, request(cell, turn), Settlement.Answered(text))

    @Test
    fun `the machine wakes from the journal and continues working cells with recovery turns`() {
        spec.assertTransition(
            OrganicAiState.Dormant,
            OrganicAiIntent.Public.Awaken,
            OrganicAiState.Awakening,
            effects = listOf(OrganicAiEffect.Restore),
        )
        val finished = organism(zygote = zygoteCell(CellPhase.Completed("done")), status = OrganismStatus.Aborted)
            .copy(id = OrganismId("o2"))
        val developing = organism(cell(C1, phase = CellPhase.Resting))
        val awakened = organism(
            cell(C1, phase = CellPhase.Resting),
            zygote = zygoteCell(working(ZYGOTE, turn = 2, isRecovery = true), turns = 2),
            version = 2,
        )
        spec.assertTransition(
            OrganicAiState.Awakening,
            OrganicAiIntent.Internal.Restored(listOf(developing, finished)),
            living(awakened, finished),
            effects = listOf(OrganicAiEffect.Revive(listOf(awakened))),
        )
    }

    @Test
    fun `waking without developing organisms revives nothing`() {
        val ended = organism(status = OrganismStatus.Aborted)
        spec.assertTransition(OrganicAiState.Awakening, OrganicAiIntent.Internal.Restored(listOf(ended)), living(ended))
        spec.assertTransition(OrganicAiState.Awakening, OrganicAiIntent.Internal.Restored(emptyList()), living())
    }

    @Test
    fun `an ended organism ignores every late request and feedback`() {
        val complaint = ImmuneCase.Complaint(CaseId("k1"), ZYGOTE, C1, "loops")
        val aborted = spec.resolve(
            living(organism(cell(C1), cases = listOf(complaint))),
            OrganicAiIntent.Public.Abort(ORGANISM),
        )!!.to
        val late = listOf(
            settled(ZYGOTE),
            settled(C1),
            OrganicAiIntent.Internal.Divide(ORGANISM, ZYGOTE, C2, "late", "late work"),
            OrganicAiIntent.Internal.Complain(ORGANISM, ImmuneCase.Complaint(CaseId("k2"), ZYGOTE, C1, "late")),
            OrganicAiIntent.Internal.Ruled(ORGANISM, complaint.id, Ruling.Kill("late")),
            OrganicAiIntent.Public.Resume(ORGANISM),
            OrganicAiIntent.Internal.Targeted(ORGANISM, TARGET),
        )
        late.forEach { spec.assertIgnored(aborted, it) }

        // A case left open by a plaintiff that already ended is dropped when the organism completes.
        val orphaned = ImmuneCase.Complaint(CaseId("k1"), C2, C1, "loops")
        val withCase = organism(
            cell(C1, phase = CellPhase.Completed("ok")),
            cell(C2, phase = CellPhase.Dead(DeathCause.Aborted)),
            cases = listOf(orphaned),
        )
        val completed = spec.resolve(living(withCase), settled(ZYGOTE))!!.to.organism()
        assertEquals(OrganismStatus.Completed("answer"), completed.status)
        assertEquals(emptyList(), completed.cases)
        spec.assertIgnored(living(completed), OrganicAiIntent.Internal.Ruled(ORGANISM, orphaned.id, Ruling.Kill("x")))
    }

    @Test
    fun `feedback of a lysed cell is ignored`() {
        val complaint = ImmuneCase.Complaint(CaseId("k1"), ZYGOTE, C1, "loops")
        val lysed = spec.resolve(
            living(organism(cell(C1), cell(C2, parent = C1), cases = listOf(complaint))),
            OrganicAiIntent.Internal.Ruled(ORGANISM, complaint.id, Ruling.Kill("loops")),
        )!!.to
        spec.assertIgnored(lysed, settled(C1))
        spec.assertIgnored(lysed, settled(C2))
        spec.assertIgnored(lysed, OrganicAiIntent.Internal.TurnAccepted(ORGANISM, C2, request(C2), TurnId("t")))
        spec.assertIgnored(lysed, OrganicAiIntent.Internal.Divide(ORGANISM, C1, C3, "late", "late work"))
    }

    @Test
    fun `a germinating cell starts over without recovery`() {
        val germinating = organism(zygote = zygoteCell().copy(session = null))
        val awakened = germinating.awakened()
        assertEquals(working(ZYGOTE, turn = 2, isRecovery = false), awakened.zygote.phase)
    }

    @Test
    fun `a failed restore leaves the journal alone and sleep always ends in Dormant`() {
        spec.assertTransition(OrganicAiState.Awakening, OrganicAiIntent.Internal.RestoreFailed, OrganicAiState.Broken)
        spec.assertTransition(
            OrganicAiState.Broken,
            OrganicAiIntent.Public.Awaken,
            OrganicAiState.Awakening,
            effects = listOf(OrganicAiEffect.Restore),
        )
        spec.assertTransition(OrganicAiState.Broken, OrganicAiIntent.Public.Sleep, OrganicAiState.Dormant)
        spec.assertTransition(OrganicAiState.Awakening, OrganicAiIntent.Public.Sleep, OrganicAiState.Dormant)
        val developing = organism()
        val aborted = organism(status = OrganismStatus.Aborted).copy(id = OrganismId("o2"))
        spec.assertTransition(
            living(developing, aborted),
            OrganicAiIntent.Public.Sleep,
            OrganicAiState.Hibernating,
            effects = listOf(OrganicAiEffect.Hibernate(listOf(developing, aborted))),
        )
        spec.assertTransition(OrganicAiState.Hibernating, OrganicAiIntent.Internal.Hibernated, OrganicAiState.Dormant)
        spec.assertIgnored(OrganicAiState.Hibernating, OrganicAiIntent.Public.Awaken)
        spec.assertIgnored(living(), OrganicAiIntent.Public.Awaken)
    }

    @Test
    fun `conceiving starts the zygote on the goal or resolves the model first`() {
        val images = listOf(ResourceRef("attachment:image", "image/png"))
        val conception = Conception(ORGANISM, GOAL, target = TARGET, attachments = images)
        val zygote = Cell(ZYGOTE, "zygote", null, GOAL, working(ZYGOTE), turns = 1)
        val conceived = Organism(
            ORGANISM, GOAL, TARGET, null, null, null, GrowthLimits(), listOf(zygote),
            version = 1,
            attachments = images,
        )
        spec.assertTransition(
            living(),
            OrganicAiIntent.Public.Conceive(conception),
            living(conceived),
            effects = listOf(
                OrganicAiEffect.Persist(conceived),
                OrganicAiEffect.Drive(conceived, ZYGOTE, request(ZYGOTE)),
            ),
        )
        val unresolved = conceived.copy(target = null)
        spec.assertTransition(
            living(),
            OrganicAiIntent.Public.Conceive(conception.copy(target = null)),
            living(unresolved),
            effects = listOf(OrganicAiEffect.Persist(unresolved), OrganicAiEffect.Resolve(ORGANISM)),
        )
        spec.assertIgnored(living(conceived), OrganicAiIntent.Public.Conceive(conception))
        spec.assertIgnored(OrganicAiState.Dormant, OrganicAiIntent.Public.Conceive(conception))
    }

    @Test
    fun `the resolved model starts the zygote, a missing one stalls it until resumed`() {
        val waiting = organism(target = null)
        val targeted = organism(version = 2)
        spec.assertTransition(
            living(waiting),
            OrganicAiIntent.Internal.Targeted(ORGANISM, TARGET),
            living(targeted),
            effects = listOf(
                OrganicAiEffect.Persist(targeted),
                OrganicAiEffect.Drive(targeted, ZYGOTE, request(ZYGOTE)),
            ),
        )
        spec.assertIgnored(living(targeted), OrganicAiIntent.Internal.Targeted(ORGANISM, TARGET))
        val stalled = organism(
            zygote = zygoteCell(CellPhase.Stalled(Breakdown.NoModel, Work.Genesis)),
            target = null,
            version = 2,
        )
        spec.assertTransition(
            living(waiting),
            OrganicAiIntent.Internal.Unresolved(ORGANISM),
            living(stalled),
            effects = listOf(OrganicAiEffect.Persist(stalled)),
        )
        val resumed = organism(
            zygote = zygoteCell(working(ZYGOTE, turn = 2, isRecovery = true), turns = 2),
            target = null,
            version = 3,
        )
        spec.assertTransition(
            living(stalled),
            OrganicAiIntent.Public.Resume(ORGANISM),
            living(resumed),
            effects = listOf(OrganicAiEffect.Persist(resumed), OrganicAiEffect.Resolve(ORGANISM)),
        )
        spec.assertIgnored(living(waiting), OrganicAiIntent.Public.Resume(ORGANISM))
    }

    @Test
    fun `aborting lyses every living cell and finishes the organism`() {
        val before = organism(cell(C1), cell(C2, phase = CellPhase.Completed("ok")))
        val after = organism(
            cell(C1, phase = CellPhase.Dead(DeathCause.Aborted)),
            cell(C2, phase = CellPhase.Completed("ok")),
            zygote = zygoteCell(CellPhase.Dead(DeathCause.Aborted)),
            status = OrganismStatus.Aborted,
            version = 2,
        )
        spec.assertTransition(
            living(before),
            OrganicAiIntent.Public.Abort(ORGANISM),
            living(after),
            effects = listOf(
                OrganicAiEffect.Persist(after),
                OrganicAiEffect.Release(after, listOf(ZYGOTE, C1), ReleaseMode.Lyse),
            ),
            outputs = listOf(OrganicAiOutput.Finished(ORGANISM, OrganismStatus.Aborted)),
        )
        spec.assertIgnored(living(after), OrganicAiIntent.Public.Abort(ORGANISM))
    }

    @Test
    fun `a working cell divides into the next cell id within its limits`() {
        val divide = OrganicAiIntent.Internal.Divide(ORGANISM, ZYGOTE, C1, "scout", "explore")
        val child = Cell(C1, "scout", ZYGOTE, "explore", working(C1), turns = 1)
        val divided = organism(child, version = 2)
        spec.assertTransition(
            living(organism()),
            divide,
            living(divided),
            effects = listOf(OrganicAiEffect.Persist(divided), OrganicAiEffect.Drive(divided, C1, request(C1))),
        )
        spec.assertIgnored(living(organism()), divide.copy(child = C2))
        spec.assertIgnored(living(organism(limits = GrowthLimits(maxCells = 1))), divide)
        spec.assertIgnored(living(organism(zygote = zygoteCell(CellPhase.Resting))), divide)
    }

    @Test
    fun `complaints and disputes open the next case for a fresh judge`() {
        val complaint = ImmuneCase.Complaint(CaseId("k1"), ZYGOTE, C1, "it loops")
        val filed = organism(cell(C1), cases = listOf(complaint), version = 2).copy(trials = listOf(Trial(complaint)))
        spec.assertTransition(
            living(organism(cell(C1))),
            OrganicAiIntent.Internal.Complain(ORGANISM, complaint),
            living(filed),
            effects = listOf(OrganicAiEffect.Persist(filed), OrganicAiEffect.Judge(filed, complaint.id)),
        )
        val againstZygote = ImmuneCase.Complaint(CaseId("k1"), C1, ZYGOTE, "it is slow")
        spec.assertIgnored(living(organism(cell(C1))), OrganicAiIntent.Internal.Complain(ORGANISM, againstZygote))
        spec.assertIgnored(
            living(organism(cell(C1))),
            OrganicAiIntent.Internal.Complain(ORGANISM, complaint.copy(id = CaseId("k2"))),
        )
        val dispute = ImmuneCase.Dispute(CaseId("k2"), C1, "Which API?", listOf(ZYGOTE))
        val asked = filed.copy(
            cases = listOf(complaint, dispute),
            trials = listOf(Trial(complaint), Trial(dispute)),
            casesFiled = 2,
            version = 3,
        )
        spec.assertTransition(
            living(filed),
            OrganicAiIntent.Internal.Dispute(ORGANISM, dispute),
            living(asked),
            effects = listOf(OrganicAiEffect.Persist(asked), OrganicAiEffect.Judge(asked, dispute.id)),
        )
        // The organism's ceilings bound the cases too.
        val noCases = organism(cell(C1), limits = GrowthLimits(maxCases = 0))
        spec.assertIgnored(living(noCases), OrganicAiIntent.Internal.Complain(ORGANISM, complaint))
        val oneOpen = asked.copy(limits = GrowthLimits(maxOpenCasesPerCell = 1))
        spec.assertIgnored(living(oneOpen), OrganicAiIntent.Internal.Dispute(ORGANISM, dispute.copy(id = CaseId("k3"))))
    }

    @Test
    fun `a trial records its judge session and its ruling`() {
        val complaint = ImmuneCase.Complaint(CaseId("k1"), ZYGOTE, C1, "loops")
        val open = organism(cell(C1), cases = listOf(complaint)).copy(trials = listOf(Trial(complaint)))
        val convened = OrganicAiIntent.Internal.JudgeConvened(ORGANISM, complaint.id, session("judge"))
        val judged = open.copy(trials = listOf(Trial(complaint, judge = session("judge"))), version = 2)
        spec.assertTransition(living(open), convened, living(judged), effects = listOf(OrganicAiEffect.Persist(judged)))
        val ruled = spec.resolve(
            living(judged),
            OrganicAiIntent.Internal.Ruled(ORGANISM, complaint.id, Ruling.Spare("fine")),
        )!!.to.organism()
        assertEquals(listOf(Trial(complaint, session("judge"), Ruling.Spare("fine"))), ruled.trials)
        spec.assertIgnored(living(ruled), convened)
        spec.assertIgnored(living(open), convened.copy(case = CaseId("k9")))
    }

    @Test
    fun `driver feedback is recorded only for the current request`() {
        val germinating = organism(zygote = zygoteCell().copy(session = null))
        val bound = organism(version = 2)
        val bind = OrganicAiIntent.Internal.SessionBound(ORGANISM, ZYGOTE, request(ZYGOTE), session("z"))
        spec.assertTransition(
            living(germinating),
            bind,
            living(bound),
            effects = listOf(OrganicAiEffect.Persist(bound)),
        )
        spec.assertIgnored(living(bound), bind)
        spec.assertIgnored(living(germinating), bind.copy(request = request(ZYGOTE, 2)))

        val accepted = OrganicAiIntent.Internal.TurnAccepted(ORGANISM, ZYGOTE, request(ZYGOTE), TurnId("t1"))
        spec.assertTransition(
            living(bound),
            accepted,
            living(bound.copy(cells = listOf(zygoteCell(working(ZYGOTE).copy(turn = TurnId("t1")))))),
        )
        spec.assertIgnored(living(bound), accepted.copy(request = request(ZYGOTE, 2)))
    }

    @Test
    fun `only an awaited permission request receives the user's decision`() {
        val pending = PermissionRequest(
            PermissionRequestId("p1"),
            TurnId("t1"),
            "Run tests",
            listOf(PermissionOption(PermissionOptionId("allow"), "Allow")),
        )
        val awaiting = organism(zygote = zygoteCell(working(ZYGOTE).copy(awaiting = listOf(pending))))
        spec.assertTransition(
            living(organism()),
            OrganicAiIntent.Internal.PermissionsChanged(ORGANISM, ZYGOTE, request(ZYGOTE), listOf(pending)),
            living(awaiting),
        )
        val decision = PermissionDecision(TurnId("t1"), PermissionRequestId("p1"), PermissionOptionId("allow"))
        spec.assertTransition(
            living(awaiting),
            OrganicAiIntent.Public.Decide(ORGANISM, ZYGOTE, decision),
            living(awaiting),
            effects = listOf(OrganicAiEffect.Respond(ORGANISM, ZYGOTE, decision)),
        )
        spec.assertIgnored(living(organism()), OrganicAiIntent.Public.Decide(ORGANISM, ZYGOTE, decision))
        spec.assertIgnored(
            living(awaiting),
            OrganicAiIntent.Public.Decide(ORGANISM, ZYGOTE, decision.copy(option = PermissionOptionId("deny"))),
        )
    }

    @Test
    fun `the zygote's final answer completes the organism`() {
        val completed = organism(
            zygote = zygoteCell(CellPhase.Completed("answer")),
            status = OrganismStatus.Completed("answer"),
            version = 2,
        )
        spec.assertTransition(
            living(organism()),
            settled(ZYGOTE),
            living(completed),
            effects = listOf(
                OrganicAiEffect.Persist(completed),
                OrganicAiEffect.Release(completed, listOf(ZYGOTE), ReleaseMode.Retire),
            ),
            outputs = listOf(OrganicAiOutput.Finished(ORGANISM, OrganismStatus.Completed("answer"))),
        )
        spec.assertIgnored(living(organism()), settled(ZYGOTE, turn = 2))
        spec.assertIgnored(living(completed), settled(ZYGOTE))
    }

    @Test
    fun `a cell explicitly waiting for children rests and wakes with an inbox reminder`() {
        val parent = organism(cell(C1), zygote = zygoteCell().copy(isAwaitingResults = true))
        val resting = parent.copy(
            cells = listOf(zygoteCell(CellPhase.Resting).copy(isAwaitingResults = true), cell(C1)),
            version = 2,
        )
        spec.assertTransition(
            living(parent),
            settled(ZYGOTE),
            living(resting),
            effects = listOf(OrganicAiEffect.Persist(resting)),
        )
        val letter = Letter.ChildFinished(C1, "cell c1", "found it")
        val woken = organism(
            cell(C1, phase = CellPhase.Completed("found it")),
            zygote = zygoteCell(working(ZYGOTE, turn = 2, work = Work.CheckInbox), turns = 2, inbox = listOf(letter)),
            version = 3,
        )
        spec.assertTransition(
            living(resting),
            settled(C1, text = "found it"),
            living(woken),
            effects = listOf(
                OrganicAiEffect.Persist(woken),
                OrganicAiEffect.Drive(woken, ZYGOTE, request(ZYGOTE, 2)),
                OrganicAiEffect.Release(woken, listOf(C1), ReleaseMode.Retire),
            ),
        )
    }

    @Test
    fun `an explicitly requested wait handles results that arrived before the turn ended`() {
        val letter = Letter.Verdict(CaseId("k1"), C2, "cell c2", VerdictOutcome.Spared, "fine")
        val busy = organism(zygote = zygoteCell(inbox = listOf(letter)).copy(isAwaitingResults = true))
        val next = organism(
            zygote = zygoteCell(working(ZYGOTE, turn = 2, work = Work.CheckInbox), turns = 2, inbox = listOf(letter)),
            version = 2,
        )
        spec.assertTransition(
            living(busy),
            settled(ZYGOTE),
            living(next),
            effects = listOf(OrganicAiEffect.Persist(next), OrganicAiEffect.Drive(next, ZYGOTE, request(ZYGOTE, 2))),
        )
    }

    @Test
    fun `a broken zygote stalls while a broken cell dies with its descendants`() {
        val failure = Breakdown.Engine(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed))
        val broke = OrganicAiIntent.Internal.TurnSettled(ORGANISM, ZYGOTE, request(ZYGOTE), Settlement.Broke(failure))
        val stalled = organism(zygote = zygoteCell(CellPhase.Stalled(failure, Work.Genesis)), version = 2)
        spec.assertTransition(
            living(organism()),
            broke,
            living(stalled),
            effects = listOf(OrganicAiEffect.Persist(stalled)),
        )

        val family = organism(
            cell(C1),
            cell(C2, parent = C1),
            zygote = zygoteCell(CellPhase.Resting).copy(isAwaitingResults = true),
        )
        val died = Letter.ChildDied(C1, "cell c1", DeathCause.Failed(failure))
        val after = organism(
            cell(C1, phase = CellPhase.Dead(DeathCause.Failed(failure))),
            cell(C2, parent = C1, phase = CellPhase.Dead(DeathCause.Orphaned(C1))),
            zygote = zygoteCell(working(ZYGOTE, turn = 2, work = Work.CheckInbox), turns = 2, inbox = listOf(died)),
            version = 2,
        )
        spec.assertTransition(
            living(family),
            broke.copy(cell = C1, request = request(C1)),
            living(after),
            effects = listOf(
                OrganicAiEffect.Persist(after),
                OrganicAiEffect.Drive(after, ZYGOTE, request(ZYGOTE, 2)),
                OrganicAiEffect.Release(after, listOf(C1, C2), ReleaseMode.Lyse),
            ),
        )
    }

    @Test
    fun `a kill lyses the accused subtree and wakes its parent and the plaintiff`() {
        val complaint = ImmuneCase.Complaint(CaseId("k1"), C3, C1, "it deletes files")
        val before = organism(
            cell(C1),
            cell(C2, parent = C1, phase = CellPhase.Resting),
            cell(C3, phase = CellPhase.Resting).copy(isAwaitingResults = true),
            zygote = zygoteCell(CellPhase.Resting).copy(isAwaitingResults = true),
            cases = listOf(complaint),
        )
        val cause = DeathCause.Lysed(complaint.id, "harmful")
        val verdict = Letter.Verdict(complaint.id, C1, "cell c1", VerdictOutcome.Killed, "harmful")
        val died = Letter.ChildDied(C1, "cell c1", cause)
        val after = organism(
            cell(C1, phase = CellPhase.Dead(cause)),
            cell(C2, parent = C1, phase = CellPhase.Dead(DeathCause.Orphaned(C1))),
            cell(C3, phase = working(C3, turn = 2, work = Work.CheckInbox), turns = 2, inbox = listOf(verdict)),
            zygote = zygoteCell(working(ZYGOTE, turn = 2, work = Work.CheckInbox), turns = 2, inbox = listOf(died)),
            casesFiled = 1,
            version = 2,
        )
        spec.assertTransition(
            living(before),
            OrganicAiIntent.Internal.Ruled(ORGANISM, complaint.id, Ruling.Kill("harmful")),
            living(after),
            effects = listOf(
                OrganicAiEffect.Persist(after),
                OrganicAiEffect.Drive(after, ZYGOTE, request(ZYGOTE, 2)),
                OrganicAiEffect.Drive(after, C3, request(C3, 2)),
                OrganicAiEffect.Release(after, listOf(C1, C2), ReleaseMode.Lyse),
            ),
        )
        spec.assertIgnored(living(after), OrganicAiIntent.Internal.Ruled(ORGANISM, complaint.id, Ruling.Kill("again")))
    }

    @Test
    fun `a spared or moot complaint only informs the plaintiff`() {
        val complaint = ImmuneCase.Complaint(CaseId("k1"), ZYGOTE, C1, "slow")
        val before = organism(cell(C1), cases = listOf(complaint))
        val spared = spec.resolve(
            living(before),
            OrganicAiIntent.Internal.Ruled(ORGANISM, complaint.id, Ruling.Spare("healthy")),
        )!!
        val zygote = spared.to.organism().zygote
        assertEquals(
            listOf(Letter.Verdict(complaint.id, C1, "cell c1", VerdictOutcome.Spared, "healthy")),
            zygote.inbox,
        )
        assertEquals(listOf(OrganicAiEffect.Persist(spared.to.organism())), spared.effects)

        val ended = before.copy(cells = listOf(zygoteCell(), cell(C1, phase = CellPhase.Completed("done"))))
        val moot = spec.resolve(
            living(ended),
            OrganicAiIntent.Internal.Ruled(ORGANISM, complaint.id, Ruling.Kill("late")),
        )!!
        assertEquals(VerdictOutcome.Moot, (moot.to.organism().zygote.inbox.single() as Letter.Verdict).outcome)
    }

    @Test
    fun `a plaintiff rests until its verdict, which wakes it`() {
        val complaint = ImmuneCase.Complaint(CaseId("k1"), ZYGOTE, C1, "loops")
        val filed = organism(
            cell(C1, phase = CellPhase.Completed("ok")),
            zygote = zygoteCell().copy(isAwaitingResults = true),
            cases = listOf(complaint),
        )
        val answered = spec.resolve(living(filed), settled(ZYGOTE))!!
        assertEquals(CellPhase.Resting, answered.to.organism().zygote.phase)
        assertEquals(OrganismStatus.Developing, answered.to.organism().status)

        val spare = OrganicAiIntent.Internal.Ruled(ORGANISM, complaint.id, Ruling.Spare("ok"))
        val ruled = spec.resolve(answered.to, spare)!!
        val verdict = Letter.Verdict(complaint.id, C1, "cell c1", VerdictOutcome.Spared, "ok")
        val woken = ruled.to.organism()
        assertEquals(working(ZYGOTE, turn = 2, work = Work.CheckInbox), woken.zygote.phase)
        assertEquals(listOf(verdict), woken.zygote.inbox)
        assertTrue(OrganicAiEffect.Drive(woken, ZYGOTE, request(ZYGOTE, 2)) in ruled.effects)
    }

    @Test
    fun `a dispute answer wakes the asker and reaches the parties`() {
        val dispute = ImmuneCase.Dispute(CaseId("k1"), C1, "Which API?", listOf(C2))
        val before = organism(
            cell(C1, phase = CellPhase.Resting).copy(isAwaitingResults = true),
            cell(C2),
            zygote = zygoteCell(CellPhase.Resting),
            cases = listOf(dispute),
        )
        val ruled = spec.resolve(
            living(before),
            OrganicAiIntent.Internal.Ruled(ORGANISM, dispute.id, Ruling.Answer("REST", "simpler")),
        )!!
        val letter = Letter.DisputeResolved(dispute.id, "Which API?", "REST", "simpler")
        val after = ruled.to.organism()
        assertEquals(working(C1, turn = 2, work = Work.CheckInbox), after.cell(C1)?.phase)
        assertEquals(listOf(letter), after.cell(C1)?.inbox)
        assertEquals(listOf(letter), after.cell(C2)?.inbox)
        assertEquals(emptyList(), after.cases)
        assertTrue(OrganicAiEffect.Drive(after, C1, request(C1, 2)) in ruled.effects)
    }

    @Test
    fun `organism intents outside Living are ignored`() {
        val intents = listOf(
            OrganicAiIntent.Public.Abort(ORGANISM),
            OrganicAiIntent.Internal.Unresolved(ORGANISM),
            settled(ZYGOTE),
        )
        for (state in listOf(OrganicAiState.Dormant, OrganicAiState.Awakening, OrganicAiState.Hibernating)) {
            intents.forEach { spec.assertIgnored(state, it) }
        }
        spec.assertIgnored(living(), OrganicAiIntent.Public.Abort(ORGANISM))
    }

    @Test
    fun `effect failures settle turns, cases and lifecycle`() {
        val organism = organism()
        val failure = EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed)
        assertEquals(
            OrganicAiIntent.Internal.TurnSettled(
                ORGANISM,
                ZYGOTE,
                request(ZYGOTE),
                Settlement.Broke(Breakdown.Engine(failure)),
            ),
            spec.onEffectFailure(OrganicAiEffect.Drive(organism, ZYGOTE, request(ZYGOTE)), EngineException(failure)),
        )
        val unknown = spec.onEffectFailure(
            OrganicAiEffect.Drive(organism, ZYGOTE, request(ZYGOTE)),
            IllegalStateException("x"),
        ) as OrganicAiIntent.Internal.TurnSettled
        assertEquals(Settlement.Broke(Breakdown.Engine(EngineFailure.Unknown())), unknown.settlement)
        assertTrue(
            spec.onEffectFailure(OrganicAiEffect.Judge(organism, CaseId("k1")), IllegalStateException()) is
                OrganicAiIntent.Internal.Ruled,
        )
        assertEquals(OrganicAiIntent.Internal.RestoreFailed, spec.onEffectFailure(OrganicAiEffect.Restore, Exception()))
        assertEquals(
            OrganicAiIntent.Internal.Unresolved(ORGANISM),
            spec.onEffectFailure(OrganicAiEffect.Resolve(ORGANISM), Exception()),
        )
        assertEquals(
            OrganicAiIntent.Internal.Hibernated,
            spec.onEffectFailure(OrganicAiEffect.Hibernate(emptyList()), Exception()),
        )
        assertEquals(null, spec.onEffectFailure(OrganicAiEffect.Persist(organism), Exception()))
    }

    @Test
    fun `the diagram names every state`() {
        val diagram = spec.toMermaid()
        listOf("Dormant", "Awakening", "Living", "Hibernating", "Broken").forEach { assertTrue(it in diagram, it) }
    }
}
