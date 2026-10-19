package io.aequicor.heartbeat.feature.harness.api.decision

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.github.GitHubChange
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflowKey
import io.aequicor.heartbeat.feature.harness.api.process.StageName
import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ProcessCommandReceiptTest {
    private val now = Instant.parse("2026-10-18T09:00:00Z")
    private val binding = ProcessActionBinding(
        HarnessId("owner"), ItemId("script"), 1,
        ProcessWorkflowKey(ProcessId("process"), "life", 1, StageName("publish")),
        2, SubjectSha("a".repeat(40)), WorkspaceRef("workspace"), "owner/repo", ProcessAuthority.Publish,
        "b".repeat(64),
    )
    private val entry = ProcessCommandDecision(
        ProcessDecision("decision", binding, now, now + 1.hours, ProcessDecisionPhase.Executing),
        ProcessCommandSnapshot("publisher", listOf("conditional"), 60_000),
    )
    private val ledger = ProcessDecisionLedger(entries = listOf(entry))
    private val edit = ProcessDecisionEdit.CompleteCommand("decision", binding, ProcessCommandReceipt.Exited(17))

    @Test
    fun `completion and native receipt are one durable value with immutable replay`() {
        val completed = assertNotNull(ledger.reduce(edit, now))
        assertEquals(ProcessDecisionPhase.Completed, completed.entries.single().decision.phase)
        assertEquals(edit.receipt, completed.entries.single().receipt)
        assertEquals(completed, Json.decodeFromString<ProcessDecisionLedger>(Json.encodeToString(completed)))
        assertEquals(completed, completed.reduce(edit, now))
        assertNull(completed.reduce(edit.copy(receipt = ProcessCommandReceipt.Exited(0)), now))
        assertNull(completed.reduce(ProcessDecisionEdit.Change("decision", ProcessDecisionCommand.Claim(binding)), now))
    }

    @Test
    fun `only the exact executing command may gain a receipt`() {
        assertNull(ledger.reduce(edit.copy(binding = binding.copy(inputRevision = 3)), now))
        assertNull(ledger.reduce(edit.copy(id = "other"), now))
        for (phase in ProcessDecisionPhase.entries.filter { it != ProcessDecisionPhase.Executing }) {
            val previous = ledger.copy(entries = listOf(entry.copy(decision = entry.decision.copy(phase = phase))))
            assertNull(previous.reduce(edit, now))
        }
        val github = entry.copy(
            command = null,
            github = ProcessGitHubSnapshot(
                GitHubRepository("owner", "repo"),
                GitHubChange.Merge(1, binding.subject),
                WriteKey("merge"),
            ),
        )
        assertNull(ledger.copy(entries = listOf(github)).reduce(edit, now))
    }

    @Test
    fun `legacy completed records remain readable without an inferred exit code`() {
        val legacy = entry.copy(decision = entry.decision.copy(phase = ProcessDecisionPhase.Completed))
        val json = Json.encodeToString(legacy)
        assertFalse(json.contains("receipt"))
        assertNull(Json.decodeFromString<ProcessCommandDecision>(json).receipt)
        assertNull(ledger.copy(entries = listOf(legacy)).reduce(edit, now))
    }

    @Test
    fun `receipt cannot be imported as a pending request or attached to another action kind`() {
        assertFailsWith<IllegalArgumentException> { entry.copy(receipt = edit.receipt) }
        val completed = entry.copy(
            decision = entry.decision.copy(phase = ProcessDecisionPhase.Completed),
            receipt = edit.receipt,
        )
        assertNull(ProcessDecisionLedger().reduce(ProcessDecisionEdit.Request(completed), now))
        assertFailsWith<IllegalArgumentException> {
            completed.copy(
                command = null,
                github = ProcessGitHubSnapshot(
                    GitHubRepository("owner", "repo"),
                    GitHubChange.Merge(1, binding.subject),
                    WriteKey("merge"),
                ),
            )
        }
    }
}
