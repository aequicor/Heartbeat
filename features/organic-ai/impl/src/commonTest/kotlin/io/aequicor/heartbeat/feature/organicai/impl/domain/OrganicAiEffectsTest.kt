package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.stripHostDirectives
import io.aequicor.heartbeat.feature.organicai.api.Breakdown
import io.aequicor.heartbeat.feature.organicai.api.CaseId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.DeathCause
import io.aequicor.heartbeat.feature.organicai.api.ImmuneCase
import io.aequicor.heartbeat.feature.organicai.api.Letter
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiEffect
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.ReleaseMode
import io.aequicor.heartbeat.feature.organicai.api.Ruling
import io.aequicor.heartbeat.feature.organicai.api.Settlement
import io.aequicor.heartbeat.feature.organicai.api.Work
import io.aequicor.heartbeat.feature.organicai.impl.C1
import io.aequicor.heartbeat.feature.organicai.impl.C2
import io.aequicor.heartbeat.feature.organicai.impl.ORGANISM
import io.aequicor.heartbeat.feature.organicai.impl.TARGET
import io.aequicor.heartbeat.feature.organicai.impl.ZYGOTE
import io.aequicor.heartbeat.feature.organicai.impl.cell
import io.aequicor.heartbeat.feature.organicai.impl.organism
import io.aequicor.heartbeat.feature.organicai.impl.request
import io.aequicor.heartbeat.feature.organicai.impl.session
import io.aequicor.heartbeat.feature.organicai.impl.working
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class OrganicAiEffectsTest : OrganicEffectsFixture() {
    @Test
    fun `automatic policy reaches zygote descendants and recovered turns`() = runTest {
        for (id in listOf(ZYGOTE, C1)) {
            for (recovery in listOf(false, true)) {
                val source = organism(cell(C1)).copy(decisionMode = AgentToolDecisionMode.AutomaticSafety)
                val recovered = source.copy(
                    cells = source.cells.map {
                        if (it.id == id) it.copy(phase = working(id, isRecovery = recovery)) else it
                    },
                )
                effects.handle(OrganicAiEffect.Drive(recovered, id, request(id)), machine)
                assertEquals(AgentToolDecisionMode.AutomaticSafety, cells.routes.last().decisionMode)
            }
        }
        effects.handle(OrganicAiEffect.Drive(organism(cell(C1)), C1, request(C1)), machine)
        assertEquals(AgentToolDecisionMode.Interactive, cells.routes.last().decisionMode)
    }

    @Test
    fun `a germinating cell binds its new session submits its genesis and settles with its answer`() = runTest {
        val germinating = organism(cell(C1).copy(session = null))
        cells.handle.pending = listOf(permission)
        effects.handle(OrganicAiEffect.Drive(germinating, C1, request(C1)), machine)
        val newSession = cells.handle.session
        assertEquals(
            listOf<OrganicAiIntent>(
                OrganicAiIntent.Internal.SessionBound(ORGANISM, C1, request(C1), newSession),
                OrganicAiIntent.Internal.TurnAccepted(ORGANISM, C1, request(C1), TurnId("t1")),
                OrganicAiIntent.Internal.PermissionsChanged(ORGANISM, C1, request(C1), listOf(permission)),
                OrganicAiIntent.Internal.TurnSettled(ORGANISM, C1, request(C1), Settlement.Answered("the answer")),
            ),
            machine.sent,
        )
        val submitted = cells.handle.submitted.single()
        assertEquals(request(C1), submitted.first)
        assertTrue("task of c1" in submitted.second)
        assertEquals(listOf<Pair<CellKey, SessionRef?>>(CellKey(ORGANISM, C1) to null), cells.opened)
    }

    @Test
    fun `goal inputs reach cell genesis and recovery but are not repeated with letters`() = runTest {
        val images = listOf(ResourceRef("attachment:image", "image/png"))
        for (id in listOf(ZYGOTE, C1)) {
            for (recovery in listOf(false, true)) {
                val organism = organism(cell(C1)).copy(attachments = images)
                val cellsWithPhase = organism.cells.map {
                    if (it.id == id) it.copy(phase = working(id, isRecovery = recovery)) else it
                }
                effects.handle(OrganicAiEffect.Drive(organism.copy(cells = cellsWithPhase), id, request(id)), machine)
                assertEquals(images, cells.handle.inputs.last())
            }
        }
        val letter = Letter.ChildFinished(C1, "child", "answer")
        val work = Work.Letters(listOf(letter))
        val organism = organism(cell(C1, phase = working(C1, work = work))).copy(attachments = images)
        effects.handle(OrganicAiEffect.Drive(organism, C1, request(C1)), machine)
        assertEquals(emptyList(), cells.handle.inputs.last())
    }

    @Test
    fun `follow-up and its recovery submit only the new message and its own inputs`() = runTest {
        val inputs = listOf(ResourceRef("attachment:follow-up", "image/png"))
        val work = Work.FollowUp("Check this revised design", inputs)
        for (recovery in listOf(false, true)) {
            val initial = organism().copy(attachments = listOf(ResourceRef("attachment:goal", "image/png")))
            val continued = initial.copy(
                cells = initial.cells.map {
                    if (it.id == ZYGOTE) it.copy(phase = working(ZYGOTE, work = work, isRecovery = recovery)) else it
                },
            )
            effects.handle(OrganicAiEffect.Drive(continued, ZYGOTE, request(ZYGOTE)), machine)
            assertEquals(work.text, stripHostDirectives(cells.handle.submitted.last().second))
            assertEquals(inputs, cells.handle.inputs.last())
            assertEquals(continued, journal.saved.last())
        }
    }

    @Test
    fun `the organism is journaled before a request reaches the engine`() = runTest {
        val organism = organism(cell(C1))
        cells.handle.onSubmit = { assertEquals(listOf(organism), journal.saved) }
        effects.handle(OrganicAiEffect.Drive(organism, C1, request(C1)), machine)
        assertEquals(1, cells.handle.submitted.size)
    }

    @Test
    fun `a cell that ended before its session was bound releases the new session`() = runTest {
        machine.refuse = { it is OrganicAiIntent.Internal.SessionBound }
        effects.handle(OrganicAiEffect.Drive(organism(cell(C1).copy(session = null)), C1, request(C1)), machine)
        assertEquals(emptyList(), cells.handle.submitted)
        assertEquals(
            listOf<Released>(Triple(CellKey(ORGANISM, C1), cells.handle.session, ReleaseMode.Lyse)),
            cells.released,
        )
    }

    @Test
    fun `a turn the machine no longer takes is cancelled and its session released`() = runTest {
        machine.refuse = { it is OrganicAiIntent.Internal.TurnAccepted }
        effects.handle(OrganicAiEffect.Drive(organism(cell(C1)), C1, request(C1)), machine)
        assertEquals(listOf(TurnId("t1")), cells.handle.cancelled)
        assertEquals(ReleaseMode.Lyse, cells.released.single().third)
    }

    @Test
    fun `a recovery adopts a native turn that is still running`() = runTest {
        cells.handle.active = TurnId("native")
        val recovering = organism(cell(C1, phase = working(C1, turn = 2, isRecovery = true)))
        effects.handle(OrganicAiEffect.Drive(recovering, C1, request(C1, 2)), machine)
        assertEquals(emptyList(), cells.handle.submitted)
        assertEquals(
            OrganicAiIntent.Internal.TurnAccepted(ORGANISM, C1, request(C1, 2), TurnId("native")),
            machine.sent.first(),
        )
        assertEquals(listOf<Pair<CellKey, SessionRef?>>(CellKey(ORGANISM, C1) to session("c1")), cells.opened)
    }

    @Test
    fun `a recovery without a running turn resubmits its work with a restart notice`() = runTest {
        val recovering = organism(cell(C1, phase = working(C1, turn = 2, isRecovery = true)))
        effects.handle(OrganicAiEffect.Drive(recovering, C1, request(C1, 2)), machine)
        assertTrue("Heartbeat restarted" in cells.handle.submitted.single().second)
    }

    @Test
    fun `a cell opens with the harnesses of its organism and connects them before its prompt`() = runTest {
        val chosen = setOf("compose", "verify")
        val germinating = organism(cell(C1).copy(session = null)).copy(harnesses = chosen)
        cells.handle.onSubmit = { assertEquals(listOf(cells.handle.session to chosen), harnesses.connected) }
        effects.handle(OrganicAiEffect.Drive(germinating, C1, request(C1)), machine)
        assertEquals(chosen, cells.routes.single().harnesses)
        assertEquals(listOf(cells.handle.session to chosen), harnesses.connected)
        assertEquals(1, cells.handle.submitted.size)
    }

    @Test
    fun `every cell of an organism runs on the same choice whoever divided it`() = runTest {
        val chosen = setOf("compose")
        val family = organism(cell(C1), cell(C2, parent = C1)).copy(harnesses = chosen)
        effects.handle(OrganicAiEffect.DriveCells(family, listOf(ZYGOTE, C1, C2)), machine)
        assertEquals(listOf(chosen, chosen, chosen), cells.routes.map { it.harnesses })
        assertEquals(3, harnesses.connected.size)
    }

    @Test
    fun `an organism without harnesses connects nothing and opens plain cells`() = runTest {
        effects.handle(OrganicAiEffect.Drive(organism(cell(C1)), C1, request(C1)), machine)
        assertEquals(emptySet(), cells.routes.single().harnesses)
        assertEquals(emptyList(), harnesses.connected)
        assertEquals(1, cells.handle.submitted.size)
    }

    @Test
    fun `a harness that cannot be connected breaks the turn before any prompt`() = runTest {
        harnesses.failure = IllegalStateException("A harness chosen for the organism could not be connected")
        val germinating = organism(cell(C1).copy(session = null)).copy(harnesses = setOf("compose"))
        effects.handle(OrganicAiEffect.Drive(germinating, C1, request(C1)), machine)
        assertEquals(emptyList(), cells.handle.submitted)
        assertEquals(emptyList(), journal.saved)
        assertEquals(
            listOf<OrganicAiIntent>(
                OrganicAiIntent.Internal.SessionBound(ORGANISM, C1, request(C1), cells.handle.session),
                OrganicAiIntent.Internal.TurnSettled(ORGANISM, C1, request(C1), Settlement.Broke(Breakdown.Harnesses)),
            ),
            machine.sent,
        )
    }

    @Test
    fun `an ended cell lets go of the harness connections of its session`() = runTest {
        val chosen = setOf("compose", "verify")
        val ended = organism(cell(C1, phase = CellPhase.Dead(DeathCause.Aborted))).copy(harnesses = chosen)
        effects.handle(OrganicAiEffect.Release(ended, listOf(ZYGOTE, C1), ReleaseMode.Lyse), machine)
        assertEquals(listOf(session("z") to chosen, session("c1") to chosen), harnesses.disconnected)
        harnesses.disconnected.clear()

        effects.handle(OrganicAiEffect.Release(ended, listOf(C1), ReleaseMode.Retire), machine)
        assertEquals(listOf(session("c1") to chosen), harnesses.disconnected)
    }

    @Test
    fun `every cell is let go before any connection is removed and each removal asks if its cell is still gone`() =
        runTest {
            val events = mutableListOf<String>()
            cells.events = events
            harnesses.events = events
            cells.letGo[CellKey(ORGANISM, C1)] = false
            val ended = organism(cell(C1, phase = CellPhase.Dead(DeathCause.Aborted)))
                .copy(harnesses = setOf("compose"))
            effects.handle(OrganicAiEffect.Release(ended, listOf(ZYGOTE, C1), ReleaseMode.Retire), machine)
            assertEquals(listOf("release zygote", "release c1", "disconnect z", "disconnect c1"), events)
            assertEquals(listOf(true, false), harnesses.guards)
            val turns = ended.cells.associate { it.id to it.turns }
            assertEquals(
                listOf(
                    CellKey(ORGANISM, ZYGOTE) to turns.getValue(ZYGOTE),
                    CellKey(ORGANISM, C1) to turns.getValue(C1),
                ),
                cells.asked,
            )
        }

    @Test
    fun `a cell lysed while it connected abandons its turn and removes the connections it made`() = runTest {
        harnesses.whileConnecting = { cells.lysed += CellKey(ORGANISM, C1) }
        val germinating = organism(cell(C1)).copy(harnesses = setOf("compose"))
        effects.handle(OrganicAiEffect.Drive(germinating, C1, request(C1)), machine)
        assertEquals(listOf(cells.handle.session to setOf("compose")), harnesses.connected)
        assertEquals(emptyList(), cells.handle.submitted)
        assertEquals(emptyList(), journal.saved)
        assertEquals(listOf(cells.handle.session to setOf("compose")), harnesses.disconnected)
        assertEquals(ReleaseMode.Lyse, cells.released.single().third)
    }

    @Test
    fun `a cell lysed while its connections failed removes what was connected and settles nothing`() = runTest {
        harnesses.whileConnecting = { cells.lysed += CellKey(ORGANISM, C1) }
        harnesses.failure = IllegalStateException("A harness chosen for the organism could not be connected")
        val germinating = organism(cell(C1)).copy(harnesses = setOf("compose", "verify"))
        effects.handle(OrganicAiEffect.Drive(germinating, C1, request(C1)), machine)
        assertEquals(emptyList(), cells.handle.submitted)
        assertEquals(listOf(cells.handle.session to setOf("compose", "verify")), harnesses.disconnected)
        assertEquals(ReleaseMode.Lyse, cells.released.single().third)
        assertEquals(emptyList(), machine.sent.filterIsInstance<OrganicAiIntent.Internal.TurnSettled>())
    }

    @Test
    fun `a cell that ended before its session was bound has connected nothing to remove`() = runTest {
        machine.refuse = { it is OrganicAiIntent.Internal.SessionBound }
        val germinating = organism(cell(C1).copy(session = null)).copy(harnesses = setOf("compose"))
        effects.handle(OrganicAiEffect.Drive(germinating, C1, request(C1)), machine)
        assertEquals(1, cells.released.size)
        assertEquals(emptyList(), harnesses.connected)
        assertEquals(emptyList(), harnesses.disconnected)
    }

    @Test
    fun `a stale retirement keeps the connections of a session that is in use again`() = runTest {
        cells.isReleased = false
        val resumed = organism(cell(C1)).copy(harnesses = setOf("compose"))
        effects.handle(OrganicAiEffect.Release(resumed, listOf(ZYGOTE), ReleaseMode.Retire), machine)
        assertEquals(1, cells.released.size)
        assertEquals(emptyList(), harnesses.disconnected)
    }

    @Test
    fun `connections that cannot be removed never keep the other cells from being released`() = runTest {
        harnesses.disconnectFailure = IllegalStateException("1 of 1 harness connections of a cell session were kept")
        val ended = organism(cell(C1, phase = CellPhase.Dead(DeathCause.Aborted))).copy(harnesses = setOf("compose"))
        effects.handle(OrganicAiEffect.Release(ended, listOf(ZYGOTE, C1), ReleaseMode.Lyse), machine)
        assertEquals(2, cells.released.size)
        assertEquals(2, harnesses.disconnected.size)
    }

    @Test
    fun `an organism without harnesses disconnects nothing when its cells end`() = runTest {
        val ended = organism(cell(C1, phase = CellPhase.Dead(DeathCause.Aborted)))
        effects.handle(OrganicAiEffect.Release(ended, listOf(ZYGOTE, C1), ReleaseMode.Lyse), machine)
        assertEquals(2, cells.released.size)
        assertEquals(emptyList(), harnesses.disconnected)
    }

    @Test
    fun `an abandoned turn lets go of the connections it made`() = runTest {
        machine.refuse = { it is OrganicAiIntent.Internal.TurnAccepted }
        val germinating = organism(cell(C1)).copy(harnesses = setOf("compose"))
        effects.handle(OrganicAiEffect.Drive(germinating, C1, request(C1)), machine)
        assertEquals(listOf(cells.handle.session to setOf("compose")), harnesses.disconnected)
    }

    @Test
    fun `a recovery turn connects again but adopting a running turn does not`() = runTest {
        val chosen = setOf("compose")
        val recovering = organism(cell(C1, phase = working(C1, turn = 2, isRecovery = true))).copy(harnesses = chosen)
        effects.handle(OrganicAiEffect.Drive(recovering, C1, request(C1, 2)), machine)
        assertEquals(listOf(session("new") to chosen), harnesses.connected)
        assertEquals(1, cells.handle.submitted.size)

        harnesses.connected.clear()
        cells.handle.submitted.clear()
        cells.handle.active = TurnId("native")
        effects.handle(OrganicAiEffect.Drive(recovering, C1, request(C1, 2)), machine)
        assertEquals(emptyList(), harnesses.connected)
        assertEquals(emptyList(), cells.handle.submitted)
    }

    @Test
    fun `a stalled zygote resumes once its harnesses can be connected`() = runTest {
        harnesses.failure = IllegalStateException("The harness library is not running")
        val organism = organism().copy(harnesses = setOf("compose"))
        effects.handle(OrganicAiEffect.Drive(organism, ZYGOTE, request(ZYGOTE)), machine)
        assertEquals(emptyList(), cells.handle.submitted)

        harnesses.failure = null
        machine.sent.clear()
        effects.handle(OrganicAiEffect.Drive(organism, ZYGOTE, request(ZYGOTE)), machine)
        assertEquals(1, cells.handle.submitted.size)
        val settled = machine.sent.last() as OrganicAiIntent.Internal.TurnSettled
        assertEquals(Settlement.Answered("the answer"), settled.settlement)
    }

    @Test
    fun `turn outcomes settle as answers or breakdowns`() = runTest {
        val failure = EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed)
        val expected = listOf(
            Triple(TurnOutcome.Failed(failure), null, Settlement.Broke(Breakdown.Engine(failure))),
            Triple(TurnOutcome.Cancelled, null, Settlement.Broke(Breakdown.Interrupted)),
            Triple(TurnOutcome.Unknown, null, Settlement.Broke(Breakdown.Unconfirmed)),
            Triple(TurnOutcome.Unknown, "found", Settlement.Answered("found")),
            Triple(
                TurnOutcome.Completed,
                null,
                Settlement.Answered("(The cell ended its turn without a written answer.)"),
            ),
        )
        expected.forEach { (outcome, answer, settlement) ->
            cells.handle.outcome = outcome
            cells.handle.answer = answer
            machine.sent.clear()
            effects.handle(OrganicAiEffect.Drive(organism(cell(C1)), C1, request(C1)), machine)
            assertEquals(settlement, (machine.sent.last() as OrganicAiIntent.Internal.TurnSettled).settlement)
        }
        // The handle hears whether the end of the turn was confirmed.
        assertEquals(listOf(true, true, false), cells.handle.unconfirmed)
    }

    @Test
    fun `every case is judged by a fresh session over its dossier`() = runTest {
        val complaint = ImmuneCase.Complaint(CaseId("k1"), ZYGOTE, C1, "it loops")
        val organism = organism(cell(C1), cases = listOf(complaint))
        judges.answer = "It loops.\nVERDICT {\"decision\":\"kill\",\"reason\":\"loops\"}"
        effects.handle(OrganicAiEffect.Judge(organism, complaint.id), machine)
        effects.handle(OrganicAiEffect.Judge(organism, complaint.id), machine)
        assertEquals(2, judges.prompts.size)
        assertTrue("looping again" in judges.prompts.first().second)
        assertEquals(TARGET, judges.prompts.first().first)
        assertEquals(
            listOf<OrganicAiIntent>(
                OrganicAiIntent.Internal.JudgeConvened(ORGANISM, complaint.id, session("judge-1")),
                OrganicAiIntent.Internal.Ruled(ORGANISM, complaint.id, Ruling.Kill("loops")),
            ),
            machine.sent.take(2),
        )
    }

    @Test
    fun `an unreadable transcript is noted in the dossier instead of failing the case`() = runTest {
        val complaint = ImmuneCase.Complaint(CaseId("k1"), ZYGOTE, C1, "it loops")
        val court = ImmunityCourt(judges) { throw EngineException(EngineFailure.Unknown()) }
        judges.answer = "VERDICT {\"decision\":\"spare\",\"reason\":\"no evidence\"}"
        val ruling = court.judge(organism(cell(C1), cases = listOf(complaint)), complaint.id)
        assertEquals(Ruling.Spare("no evidence"), ruling)
        assertTrue("(the transcript could not be read)" in judges.prompts.single().second)
    }

    @Test
    fun `a revival saves the awakened organism before it drives cells and judges cases`() = runTest {
        val complaint = ImmuneCase.Complaint(CaseId("k1"), ZYGOTE, C1, "it loops")
        val organism = organism(cell(C1), cell(C2, phase = CellPhase.Resting), cases = listOf(complaint))
        judges.answer = "nothing"
        cells.handle.onSubmit = { assertEquals(organism, journal.saved.first()) }
        effects.handle(OrganicAiEffect.Revive(listOf(organism)), machine)
        assertEquals(2, cells.handle.submitted.size)
        assertTrue(machine.sent.any { it is OrganicAiIntent.Internal.Ruled })
        assertEquals(2, machine.sent.count { it is OrganicAiIntent.Internal.TurnSettled })
    }

    @Test
    fun `a revived turn that fails settles as broken`() = runTest {
        cells.failOpen = EngineException(EngineFailure.Unknown())
        effects.handle(OrganicAiEffect.Revive(listOf(organism())), machine)
        val settled = machine.sent.single() as OrganicAiIntent.Internal.TurnSettled
        assertEquals(Settlement.Broke(Breakdown.Engine(EngineFailure.Unknown())), settled.settlement)
    }

    @Test
    fun `a batch drives only its listed cells without resubmitting running siblings or judges`() = runTest {
        val complaint = ImmuneCase.Complaint(CaseId("k1"), ZYGOTE, C1, "it loops")
        val organism = organism(cell(C1), cell(C2), cases = listOf(complaint))
        effects.handle(OrganicAiEffect.DriveCells(organism, listOf(ZYGOTE, C1)), machine)
        assertEquals(listOf(CellKey(ORGANISM, ZYGOTE), CellKey(ORGANISM, C1)), cells.opened.map { it.first })
        assertEquals(2, cells.handle.submitted.size)
        assertEquals(2, machine.sent.count { it is OrganicAiIntent.Internal.TurnSettled })
        assertTrue(judges.prompts.isEmpty())
    }

    @Test
    fun `an organism without a model is resolved first`() = runTest {
        effects.handle(OrganicAiEffect.Revive(listOf(organism(target = null))), machine)
        assertEquals(listOf<OrganicAiIntent>(OrganicAiIntent.Internal.Targeted(ORGANISM, TARGET)), machine.sent)
        val missing = OrganicAiEffects(
            journal,
            { null },
            cells,
            CellDriver(cells, journal, workspaces, harnesses),
            ImmunityCourt(judges, transcripts),
            workspaces,
        )
        machine.sent.clear()
        missing.handle(OrganicAiEffect.Resolve(ORGANISM), machine)
        assertEquals(listOf<OrganicAiIntent>(OrganicAiIntent.Internal.Unresolved(ORGANISM)), machine.sent)
    }

    @Test
    fun `restore hibernate release and respond reach their ports`() = runTest {
        journal.stored = listOf(organism())
        effects.handle(OrganicAiEffect.Restore, machine)
        assertEquals(OrganicAiIntent.Internal.Restored(listOf(organism())), machine.sent.single())

        effects.handle(OrganicAiEffect.Hibernate(listOf(organism())), machine)
        assertEquals(1, cells.releasedAll)
        assertEquals(OrganicAiIntent.Internal.Hibernated, machine.sent.last())

        val ended = organism(cell(C1, phase = CellPhase.Dead(DeathCause.Aborted)))
        effects.handle(OrganicAiEffect.Release(ended, listOf(ZYGOTE, C1), ReleaseMode.Lyse), machine)
        assertEquals(
            listOf<Released>(
                Triple(CellKey(ORGANISM, ZYGOTE), session("z"), ReleaseMode.Lyse),
                Triple(CellKey(ORGANISM, C1), session("c1"), ReleaseMode.Lyse),
            ),
            cells.released,
        )

        val decision = PermissionDecision(TurnId("t1"), permission.id, PermissionOptionId("allow"))
        effects.handle(OrganicAiEffect.Respond(ORGANISM, C1, decision), machine)
        assertEquals(listOf(CellKey(ORGANISM, C1) to decision), cells.responses)
    }
}
