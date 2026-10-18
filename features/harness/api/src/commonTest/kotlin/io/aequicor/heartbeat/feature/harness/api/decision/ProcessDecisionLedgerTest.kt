package io.aequicor.heartbeat.feature.harness.api.decision

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
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

class ProcessDecisionLedgerTest {
    private val now = Instant.parse("2026-10-18T09:00:00Z")
    private val binding = ProcessActionBinding(
        HarnessId("owner"), ItemId("script"), 1,
        ProcessWorkflowKey(ProcessId("process"), "incarnation", 1, StageName("merge")),
        2, SubjectSha("a".repeat(40)), WorkspaceRef("workspace"), "owner/repo", ProcessAuthority.Merge,
        "b".repeat(64),
    )
    private val entry = ProcessCommandDecision(
        ProcessDecision("decision_1", binding, now, now + 1.hours),
        ProcessCommandSnapshot("gh", listOf("pr", "merge", "1"), 60_000),
    )
    private val empty = ProcessDecisionLedger()

    @Test
    fun `legacy command payload decodes without GitHub fields and typed decisions round trip`() {
        val legacy = Json.encodeToString(entry)
        assertFalse(legacy.contains("github"))
        assertEquals(entry, Json.decodeFromString<ProcessCommandDecision>(legacy))
        val typed = entry.copy(
            command = null,
            github = ProcessGitHubSnapshot(
                GitHubRepository("owner", "repo"),
                GitHubChange.Merge(1, binding.subject),
                WriteKey("merge_1"),
            ),
        )
        val stored = ProcessDecisionLedger(entries = listOf(typed))
        assertEquals(stored, Json.decodeFromString<ProcessDecisionLedger>(Json.encodeToString(stored)))
        assertEquals(stored, stored.reduce(ProcessDecisionEdit.Request(typed), now))
        assertNull(stored.reduce(ProcessDecisionEdit.Request(entry), now))
    }

    @Test
    fun `missing or ambiguous payload never creates a host entry`() {
        assertFailsWith<IllegalArgumentException> { ProcessCommandDecision(entry.decision) }
        assertFailsWith<IllegalArgumentException> {
            entry.copy(
                github = ProcessGitHubSnapshot(
                    GitHubRepository("owner", "repo"),
                    GitHubChange.Merge(1, binding.subject),
                    WriteKey("merge_1"),
                ),
            )
        }
    }

    @Test
    fun `an imported approval or dispatch cannot create host consent`() {
        for (phase in ProcessDecisionPhase.entries.filter { it != ProcessDecisionPhase.Pending }) {
            val forged = entry.copy(decision = entry.decision.copy(phase = phase))
            assertNull(empty.reduce(ProcessDecisionEdit.Request(forged), now))
        }
    }

    @Test
    fun `exact replay retains the original deadline id and refusal`() {
        val added = assertNotNull(empty.reduce(ProcessDecisionEdit.Request(entry), now))
        val denied = assertNotNull(
            added.reduce(
                ProcessDecisionEdit.Change(entry.decision.id, ProcessDecisionCommand.Answer(0, false)),
                now,
            ),
        )
        val retry = entry.copy(decision = entry.decision.copy(id = "new_request", expiresAt = now + 2.hours))
        assertEquals(denied, denied.reduce(ProcessDecisionEdit.Request(retry), now))
        val changed = retry.copy(command = checkNotNull(entry.command).copy(program = "git"))
        assertNull(denied.reduce(ProcessDecisionEdit.Request(changed), now))
        assertEquals(denied, Json.decodeFromString<ProcessDecisionLedger>(Json.encodeToString(denied)))
    }

    @Test
    fun `host request stays invisible until exact storage receipt and stale receipts cannot approve`() {
        val edit = ProcessDecisionsIntent.Internal.Edit("request", ProcessDecisionEdit.Request(entry), now)
        val next = assertNotNull(empty.reduce(edit.edit, now))
        val write = ProcessDecisionWrite("request", next)
        val ready = ProcessDecisionsState.Ready(empty)
        val pending = ready.copy(pending = write)
        ProcessDecisionsMachineSpec.assertTransition(
            ready,
            edit,
            pending,
            effects = listOf(ProcessDecisionsEffect.Save(write, 0)),
        )
        ProcessDecisionsMachineSpec.assertIgnored(
            pending,
            ProcessDecisionsIntent.Internal.Saved(write.copy(request = "other")),
        )
        ProcessDecisionsMachineSpec.assertTransition(
            pending,
            ProcessDecisionsIntent.Internal.Saved(write),
            ProcessDecisionsState.Ready(next),
            outputs = listOf(ProcessDecisionsOutput("request", true)),
        )
    }

    @Test
    fun `busy and illegal edits are refused and uncertain commit forces reload`() {
        val write = ProcessDecisionWrite("first", empty.copy(revision = 1))
        val pending = ProcessDecisionsState.Ready(empty, write)
        val edit = ProcessDecisionsIntent.Internal.Edit("second", ProcessDecisionEdit.Request(entry), now)
        ProcessDecisionsMachineSpec.assertTransition(
            pending,
            edit,
            pending,
            outputs = listOf(ProcessDecisionsOutput("second", false)),
        )
        ProcessDecisionsMachineSpec.assertTransition(
            pending,
            ProcessDecisionsIntent.Internal.Failed,
            ProcessDecisionsState.Failed,
            outputs = listOf(ProcessDecisionsOutput("first", false)),
        )
        ProcessDecisionsMachineSpec.assertTransition(
            ProcessDecisionsState.Failed,
            ProcessDecisionsIntent.Public.Load,
            ProcessDecisionsState.Loading,
            effects = listOf(ProcessDecisionsEffect.Load),
        )
    }

    @Test
    fun `restoration reads only committed book and exact request replay does not save again`() {
        val ledger = assertNotNull(empty.reduce(ProcessDecisionEdit.Request(entry), now))
        ProcessDecisionsMachineSpec.assertTransition(
            ProcessDecisionsState.Idle,
            ProcessDecisionsIntent.Public.Load,
            ProcessDecisionsState.Loading,
            effects = listOf(ProcessDecisionsEffect.Load),
        )
        ProcessDecisionsMachineSpec.assertTransition(
            ProcessDecisionsState.Loading,
            ProcessDecisionsIntent.Internal.Loaded(ledger),
            ProcessDecisionsState.Ready(ledger),
        )
        val ready = ProcessDecisionsState.Ready(ledger)
        ProcessDecisionsMachineSpec.assertTransition(
            ready,
            ProcessDecisionsIntent.Internal.Edit("replay", ProcessDecisionEdit.Request(entry), now),
            ready,
            outputs = listOf(ProcessDecisionsOutput("replay", true)),
        )
        ProcessDecisionsMachineSpec.assertTransition(
            ProcessDecisionsState.Loading,
            ProcessDecisionsIntent.Internal.Failed,
            ProcessDecisionsState.Failed,
        )
    }
}
