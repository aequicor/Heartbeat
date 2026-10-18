package io.aequicor.heartbeat.feature.harness.impl.presentation

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessActionBinding
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessCommandDecision
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessCommandSnapshot
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecision
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionLedger
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflowKey
import io.aequicor.heartbeat.feature.harness.api.process.StageName
import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import io.aequicor.heartbeat.feature.harness.impl.data.decision.DecisionTestMachine
import io.aequicor.heartbeat.feature.harness.impl.data.services.ProcessesTestClock
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.ProcessDecisionHost
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.ProcessDecisionStorage
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class HarnessProcessDecisionsModelTest {
    private val entry = ProcessCommandDecision(
        ProcessDecision(
            "decision",
            ProcessActionBinding(
                HarnessId("harness"), ItemId("item"), 1,
                ProcessWorkflowKey(ProcessId("process"), "incarnation", 1, StageName("merge")),
                2, SubjectSha("a".repeat(40)), WorkspaceRef("workspace"), "github.com/owner/repo",
                ProcessAuthority.Merge, "b".repeat(64),
            ),
            Instant.fromEpochSeconds(100),
            Instant.fromEpochSeconds(200),
        ),
        ProcessCommandSnapshot("gh", listOf("pr", "merge", "1"), 60_000),
    )

    @Test
    fun `answer after deadline shows expired refusal without a storage error`() = runTest {
        val storage = DecisionModelStorage(ProcessDecisionLedger(entries = listOf(entry)))
        val clock = ProcessesTestClock(150)
        val host = ProcessDecisionHost(DecisionTestMachine(storage), clock)
        val model = HarnessProcessDecisionsModel("harness", host, storeFactory(), backgroundScope, clock)
        val screen = subscribe(model.store)
        assertEquals(ProcessDecisionStatusUi.Pending, screen.states.value.rows.single().status)

        clock.seconds = 200
        screen.intent(ProcessDecisionsUiIntent.Answer("decision", 0, true))
        runCurrent()

        assertEquals(ProcessDecisionStatusUi.Expired, screen.states.value.rows.single().status)
        assertTrue(screen.states.value.isRefused)
        assertFalse(screen.states.value.isFailed)
        assertEquals(0, storage.writes)

        screen.intent(ProcessDecisionsUiIntent.Reload)
        runCurrent()
        assertFalse(screen.states.value.isRefused)
        assertEquals(ProcessDecisionStatusUi.Expired, screen.states.value.rows.single().status)
    }

    @Test
    fun `reopened expired request is shown as expired and stale click is not a storage error`() = runTest {
        val storage = DecisionModelStorage(ProcessDecisionLedger(entries = listOf(entry)))
        val clock = ProcessesTestClock(201)
        val model = HarnessProcessDecisionsModel(
            "harness",
            ProcessDecisionHost(DecisionTestMachine(storage), clock),
            storeFactory(),
            backgroundScope,
            clock,
        )
        val screen = subscribe(model.store)
        assertEquals(ProcessDecisionStatusUi.Expired, screen.states.value.rows.single().status)

        screen.intent(ProcessDecisionsUiIntent.Answer("decision", 8, true))
        runCurrent()
        assertTrue(screen.states.value.isRefused)
        assertFalse(screen.states.value.isFailed)
        assertEquals(0, storage.writes)
    }
}

private class DecisionModelStorage(private val ledger: ProcessDecisionLedger) : ProcessDecisionStorage {
    var writes = 0
    override suspend fun load(): ProcessDecisionLedger = ledger
    override suspend fun save(ledger: ProcessDecisionLedger, expectedRevision: Long) {
        writes++
    }
}
