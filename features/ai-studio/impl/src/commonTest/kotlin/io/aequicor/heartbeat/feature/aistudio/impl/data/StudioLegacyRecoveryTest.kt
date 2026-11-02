package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RestoresSessionTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeExpectedAction
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRun
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioLegacyRecoveryTest {
    @Test
    fun `proven legacy human terminal survives restart without granting continuation or losing Stop`() = runTest {
        val f = NativeHelperFixture()
        val source = checkNotNull(f.turn.request)
        val record = f.record.copy(
            helper = null,
            lastRunRequest = source,
            humanAuthority = StudioHumanAuthority(listOf(StudioHumanInstruction(source, "Original human request"))),
        )
        val stores = ChecklistTestStores()
        stores.keyValue(ChatSpec).set(ChatsKey, listOf(record))
        val attempts = StudioHelperAttempts(stores)
        val observation = StudioLegacyRecovery(lazyOf(f.native)).inspect(
            record,
            source,
            f.inspecting(TurnInspection.Observed(source, TurnOutcome.Completed)),
        )
        val runs = LegacyRuns(observation)
        val admissions = emptyHelperAdmissions()
        val continuation = StudioContinuationAdmission(
            attempts,
            admissions,
            StudioWorktrees(ConfigurationRegistry(), NoAgentTools),
            lazyOf(runs),
        )
        val request = runRequest("C").copy(id = record.id, continuationOf = source)
        val producer = legacyProducer(record, request, observation)
        repeat(2) {
            assertFailsWith<StudioLegacySnapshotUnavailable> { continuation.prepare(record, request, producer) }
            assertNull(attempts.receipt(HelperId(record.id), source))
            assertNull(attempts.receipt(HelperId(record.id), request.request))
            assertTrue(checkNotNull(attempts.legacy(HelperId(record.id), source)).isHumanSourceConfirmed)
            attempts.stopLegacy(HelperId(record.id), source)
        }
        val restored = StudioHelperAttempts(stores)
        val retained = checkNotNull(restored.legacy(HelperId(record.id), source))
        assertTrue(retained.isHumanSourceConfirmed)
        assertTrue(retained.isStopRequested)
        val independent = runRequest("H").copy(id = record.id, origin = StudioTurnOrigin.Conversation)
        StudioHelperAdmission(restored, admissions, lazyOf(runs)).prepare(record, independent)
        assertEquals(
            StudioAttemptPolicy.Conversation,
            restored.receipt(HelperId(record.id), independent.request)?.policy,
        )
        assertNull(restored.receipt(HelperId(record.id), source))
    }

    @Test
    fun `legacy classification rejects incomplete provenance and mismatched native evidence`() = runTest {
        val f = NativeHelperFixture()
        val source = checkNotNull(f.turn.request)
        val record = f.record.copy(
            helper = null,
            lastRunRequest = source,
            humanAuthority = StudioHumanAuthority(listOf(StudioHumanInstruction(source, "Original"))),
        )
        val observation = StudioLegacyRecovery(lazyOf(f.native)).inspect(
            record,
            source,
            f.inspecting(TurnInspection.Observed(source, TurnOutcome.Completed)),
        )
        val request = runRequest("C").copy(id = record.id, continuationOf = source)
        val claim = StudioLegacyHumanClaim(request, legacyProducer(record, request, observation))
        for (invalid in listOf(
            record.copy(humanAuthority = null),
            record.copy(humanAuthority = record.humanAuthority?.copy(isComplete = false)),
            record.copy(humanAuthority = StudioHumanAuthority()),
            record.copy(helper = f.record.helper),
            record.copy(identity = StudioSessionIdentity(StudioIdentityOrigin.Unresolved)),
            record.copy(hasAttemptJournal = true),
            record.copy(ref = f.ref.copy(nativeId = "other")),
        )) {
            val stores = ChecklistTestStores()
            stores.keyValue(ChatSpec).set(ChatsKey, listOf(invalid))
            val attempts = StudioHelperAttempts(stores)
            assertFailsWith<IllegalStateException> { attempts.rememberLegacy(HelperId(record.id), observation, claim) }
            assertNull(attempts.legacy(HelperId(record.id), source))
        }
        val canonical = resolvedStudioIdentities(listOf(record)).single()
        for (invalid in listOf(
            observation.copy(turn = null),
            observation.copy(turn = TurnId("other")),
            observation.copy(request = RequestId("other")),
            observation.copy(phase = StudioLegacyPhase.Unknown, outcome = null),
            observation.copy(outcome = StudioHelperTerminalOutcome.Cancelled),
        )) {
            assertFailsWith<IllegalStateException> { claim.confirm(canonical, invalid) }
        }
        assertFailsWith<IllegalStateException> {
            claim.copy(
                producer = claim.producer.copy(virtualInterruptedRequests = emptyList()),
            ).confirm(canonical, observation)
        }
    }

    @Test
    fun `fresh Idle admits a root writer and retains legacy uncertainty without a send receipt`() = runTest {
        val f = NativeHelperFixture()
        val stores = ChecklistTestStores()
        val source = checkNotNull(f.turn.request)
        val record = f.record.copy(helper = null, lastRunRequest = source)
        stores.keyValue(ChatSpec).set(ChatsKey, listOf(record))
        val attempts = StudioHelperAttempts(stores)
        val observation = StudioLegacyRecovery(
            lazyOf(f.native),
        ).inspect(record, source, f.inspecting(TurnInspection.Idle))
        val runs = LegacyRuns(observation)
        val admission = StudioHelperAdmission(attempts, emptyHelperAdmissions(), lazyOf(runs))
        val request = runRequest("new-Q").copy(id = record.id, origin = StudioTurnOrigin.Conversation)
        val admitted = admission.prepareNewTurn(record, request) {
            stores.keyValue(
                ChatSpec,
            ).set(ChatsKey, listOf(record.startedRun(request.request).copy(hasAttemptJournal = true)))
        }
        val helper = HelperId(record.id)

        assertEquals(StudioLegacyPhase.Idle, attempts.legacy(helper, source)?.phase)
        assertNull(attempts.receipt(helper, source))
        assertNull(attempts.legacy(helper, source)?.terminalResult())
        assertEquals(StudioHelperPhase.Preparing, attempts.receipt(helper, request.request)?.phase)
        assertTrue(checkNotNull(stores.keyValue(ChatSpec).get(ChatsKey)).single().hasAttemptJournal)
        assertNotNull(admitted.submission).cancel()
        assertFailsWith<EngineException> { attempts.requestStop(helper, source) }
        assertTrue(checkNotNull(attempts.legacy(helper, source)).isStopRequested)
        assertFailsWith<CancellationException> {
            attempts.prepareDelivery(
                helper,
                HelperPrompt(RequestId("revive"), "work"),
                StudioAttemptPolicy.GraphWork(source),
            )
        }
        assertTrue(attempts.prepareConversation(helper, HelperPrompt(RequestId("independent"), "human")).isNew)
        assertNull(attempts.receipt(helper, source))
    }

    @Test
    fun `terminal only Cancelled is retained without a fabricated turn or native send receipt`() = runTest {
        val f = NativeHelperFixture()
        val source = checkNotNull(f.turn.request)
        val record = f.record.copy(helper = null, lastRunRequest = source)
        f.state.value = ActiveSessionState.Ready()
        val observation = StudioLegacyRecovery(lazyOf(f.native)).inspect(
            record,
            source,
            f.inspecting(TurnInspection.Observed(source, TurnOutcome.Cancelled)),
        )
        val stores = ChecklistTestStores()
        stores.keyValue(ChatSpec).set(ChatsKey, listOf(record))
        val attempts = StudioHelperAttempts(stores)
        attempts.rememberLegacy(HelperId(record.id), observation)

        assertNull(observation.turn)
        assertEquals(TurnOutcome.Cancelled, observation.terminalResult()?.outcome)
        assertFalse(checkNotNull(observation.terminalResult()).isAnswerComplete)
        assertNull(attempts.receipt(HelperId(record.id), source))
    }

    @Test
    fun `legacy session without a request address cannot admit a new writer`() = runTest {
        val record = NativeHelperFixture().record.copy(helper = null, lastRunRequest = null)
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        val admission = StudioHelperAdmission(attempts, emptyHelperAdmissions())
        val request = runRequest("new-Q").copy(id = record.id)

        assertFailsWith<EngineException> { admission.prepareNewTurn(record, request) { error("Must not admit") } }
        assertNull(attempts.receipt(HelperId(record.id), request.request))
    }

    @Test
    fun `contradictory legacy native turn identities are rejected`() = runTest {
        val f = NativeHelperFixture()
        val source = checkNotNull(f.turn.request)
        val record = f.record.copy(helper = null, lastRunRequest = source)
        val observation = StudioLegacyRecovery(lazyOf(f.native)).inspect(
            record,
            source,
            f.inspecting(TurnInspection.Observed(source, TurnOutcome.Completed)),
        ).copy(turn = TurnId("first"))

        assertFailsWith<IllegalStateException> { observation.updated(observation.copy(turn = TurnId("other"))) }
    }

    @Test
    fun `cached empty Ready and unknown inspector do not authorize legacy migration`() = runTest {
        val f = NativeHelperFixture()
        val source = checkNotNull(f.turn.request)
        val record = f.record.copy(helper = null, lastRunRequest = source)
        val recovery = StudioLegacyRecovery(lazyOf(f.native))
        f.state.value = ActiveSessionState.Ready()
        assertEquals(StudioLegacyPhase.Unknown, recovery.inspect(record, source, f).phase)
        for (inspection in listOf(TurnInspection.Unknown, TurnInspection.Observed(source, null))) {
            val observation = recovery.inspect(record, source, f.inspecting(inspection))
            val stores = ChecklistTestStores()
            stores.keyValue(ChatSpec).set(ChatsKey, listOf(record))
            val attempts = StudioHelperAttempts(stores)
            val admission = StudioHelperAdmission(attempts, emptyHelperAdmissions(), lazyOf(LegacyRuns(observation)))
            var isRecorded = false
            assertFailsWith<EngineException> {
                admission.prepareNewTurn(record, runRequest("new-Q").copy(id = record.id)) { isRecorded = true }
            }
            assertFalse(isRecorded)
            assertNull(attempts.legacy(HelperId(record.id), source))
        }
    }
}

private fun NativeHelperFixture.inspecting(inspection: TurnInspection): ActiveSession {
    val restores = object : RestoresSessionTurns {
        override suspend fun checkpoint(request: RequestId): String? = null
        override suspend fun inspect(checkpoint: String?): TurnInspection = inspection
    }
    return object : ActiveSession by this {
        override val features = nativeFeatures(restores)
    }
}

private class LegacyRuns(private val observation: StudioLegacyObservation) : StudioHelperRuns {
    override suspend fun reconcileLegacy(helper: HelperId, request: RequestId): StudioLegacyObservation = observation
    override suspend fun helperTerminal(helper: HelperId, request: RequestId): StudioHelperTerminal? = null
    override suspend fun stopHelper(helper: HelperId, request: RequestId): StudioHelperTerminal? = null
    override suspend fun runHelper(
        helper: HelperId,
        prompt: HelperPrompt,
        submission: StudioHelperSubmission,
        onAccepted: suspend (SessionRef, TurnId) -> Unit,
    ): Unit = error("Migration must not launch native work")
}

private fun legacyProducer(
    record: StudioChatRecord,
    request: StudioTurnRequest,
    observation: StudioLegacyObservation,
): WorktreeTask = WorktreeTask(
    record.id,
    WorkspaceRef("project"),
    run = WorktreeRun(
        observation.request,
        session = observation.session,
        turn = observation.turn,
        outcome = observation.terminalResult()?.outcome,
    ),
    expectedAction = WorktreeExpectedAction(request.request.value, WorktreeRunKind.Coding, observation.request),
    virtualInterruptedRequests = listOf(observation.request),
)
