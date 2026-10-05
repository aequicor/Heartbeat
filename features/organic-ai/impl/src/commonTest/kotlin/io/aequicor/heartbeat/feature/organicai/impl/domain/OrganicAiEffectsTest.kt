package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.organicai.api.Breakdown
import io.aequicor.heartbeat.feature.organicai.api.CaseId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.DeathCause
import io.aequicor.heartbeat.feature.organicai.api.ImmuneCase
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiEffect
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.ReleaseMode
import io.aequicor.heartbeat.feature.organicai.api.Ruling
import io.aequicor.heartbeat.feature.organicai.api.Settlement
import io.aequicor.heartbeat.feature.organicai.impl.C1
import io.aequicor.heartbeat.feature.organicai.impl.C2
import io.aequicor.heartbeat.feature.organicai.impl.ORGANISM
import io.aequicor.heartbeat.feature.organicai.impl.TARGET
import io.aequicor.heartbeat.feature.organicai.impl.ZYGOTE
import io.aequicor.heartbeat.feature.organicai.impl.cell
import io.aequicor.heartbeat.feature.organicai.impl.message
import io.aequicor.heartbeat.feature.organicai.impl.organism
import io.aequicor.heartbeat.feature.organicai.impl.request
import io.aequicor.heartbeat.feature.organicai.impl.session
import io.aequicor.heartbeat.feature.organicai.impl.working
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrganicAiEffectsTest {
    private val journal = Journal()
    private val cells = Cells()
    private val judges = Judges()
    private val transcripts = SessionTranscripts { listOf(message(MessageRole.Assistant, "looping again")) }
    private val effects = OrganicAiEffects(
        journal,
        { TARGET },
        cells,
        CellDriver(cells, journal),
        ImmunityCourt(judges, transcripts),
    )
    private val machine = Recorder()

    @Test
    fun `a germinating cell binds its new session, submits its genesis and settles with its answer`() = runTest {
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
    fun `an organism without a model is resolved first`() = runTest {
        effects.handle(OrganicAiEffect.Revive(listOf(organism(target = null))), machine)
        assertEquals(listOf<OrganicAiIntent>(OrganicAiIntent.Internal.Targeted(ORGANISM, TARGET)), machine.sent)
        val missing = OrganicAiEffects(
            journal,
            { null },
            cells,
            CellDriver(cells, journal),
            ImmunityCourt(judges, transcripts),
        )
        machine.sent.clear()
        missing.handle(OrganicAiEffect.Resolve(ORGANISM), machine)
        assertEquals(listOf<OrganicAiIntent>(OrganicAiIntent.Internal.Unresolved(ORGANISM)), machine.sent)
    }

    @Test
    fun `restore, hibernate, release and respond reach their ports`() = runTest {
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

    private val permission = PermissionRequest(
        PermissionRequestId("p1"),
        TurnId("t1"),
        "Run",
        listOf(PermissionOption(PermissionOptionId("allow"), "Allow")),
    )

    private class Recorder : EffectScope<OrganicAiIntent> {
        val sent = mutableListOf<OrganicAiIntent>()
        var refuse: (OrganicAiIntent) -> Boolean = { false }

        override suspend fun send(intent: OrganicAiIntent): SendResult {
            sent += intent
            return if (refuse(intent)) SendResult.Ignored else SendResult.Accepted
        }
    }

    private class Journal : OrganismJournal {
        var stored = emptyList<Organism>()
        val saved = mutableListOf<Organism>()
        override suspend fun load(): List<Organism> = stored
        override suspend fun save(organism: Organism) {
            saved += organism
        }
    }

    private class Judges : JudgeSessions {
        var answer = ""
        val prompts = mutableListOf<Pair<EngineTarget, String>>()
        override suspend fun deliberate(
            target: EngineTarget,
            prompt: String,
            onSession: suspend (SessionRef) -> Unit,
        ): String {
            prompts += target to prompt
            onSession(session("judge-${prompts.size}"))
            return answer
        }
    }

    private class Cells : CellSessions {
        val handle = Handle()
        var failOpen: Exception? = null
        val opened = mutableListOf<Pair<CellKey, SessionRef?>>()
        val released = mutableListOf<Released>()
        val responses = mutableListOf<Pair<CellKey, PermissionDecision>>()
        var releasedAll = 0

        override suspend fun open(key: CellKey, route: CellRoute, existing: SessionRef?): CellHandle {
            failOpen?.let { throw it }
            opened += key to existing
            return handle
        }

        override suspend fun respond(key: CellKey, decision: PermissionDecision) {
            responses += key to decision
        }

        override suspend fun release(key: CellKey, session: SessionRef?, mode: ReleaseMode) {
            released += Triple(key, session, mode)
        }

        override suspend fun releaseAll() {
            releasedAll++
        }
    }

    private class Handle : CellHandle {
        override val session: SessionRef = session("new")
        var active: TurnId? = null
        var pending = emptyList<PermissionRequest>()
        var outcome: TurnOutcome = TurnOutcome.Completed
        var answer: String? = "the answer"
        var onSubmit: () -> Unit = {}
        val submitted = mutableListOf<Pair<RequestId, String>>()
        val cancelled = mutableListOf<TurnId>()

        override fun activeTurn(): TurnId? = active

        override suspend fun submit(request: RequestId, text: String, trust: TrustLevel?): TurnId {
            onSubmit()
            submitted += request to text
            return TurnId("t1")
        }

        override suspend fun cancel(turn: TurnId) {
            cancelled += turn
        }

        override suspend fun await(turn: TurnId, onPending: suspend (List<PermissionRequest>) -> Unit): TurnOutcome {
            if (pending.isNotEmpty()) onPending(pending)
            return outcome
        }

        val unconfirmed = mutableListOf<Boolean>()

        override suspend fun answer(turn: TurnId, isUnconfirmed: Boolean): String? = answer.also {
            unconfirmed += isUnconfirmed
        }
    }
}

private typealias Released = Triple<CellKey, SessionRef?, ReleaseMode>
