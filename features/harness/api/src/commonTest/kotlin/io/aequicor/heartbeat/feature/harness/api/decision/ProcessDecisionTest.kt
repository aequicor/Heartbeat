package io.aequicor.heartbeat.feature.harness.api.decision

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflowKey
import io.aequicor.heartbeat.feature.harness.api.process.StageName
import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ProcessDecisionTest {
    private val now = Instant.parse("2026-10-18T09:00:00Z")
    private val binding = ProcessActionBinding(
        HarnessId("harness"), ItemId("item"), 1,
        ProcessWorkflowKey(ProcessId("process"), "incarnation", 1, StageName("merge")),
        2, SubjectSha("a".repeat(40)), WorkspaceRef("workspace"), "https://github.com/owner/repo",
        ProcessAuthority.Merge, "b".repeat(64),
    )
    private val pending = ProcessDecision("decision_1", binding, now, now + 1.hours)

    @Test
    fun `pending request and constructed binding never permit execution`() {
        assertFalse(pending.permits(binding, now))
        assertNull(pending.reduce(ProcessDecisionCommand.Claim(binding), now))
        assertNull(pending.reduce(ProcessDecisionCommand.Complete, now))
    }

    @Test
    fun `answer fences the displayed revision and expires at deadline`() {
        assertNull(pending.reduce(ProcessDecisionCommand.Answer(1, true), now))
        assertNull(pending.reduce(ProcessDecisionCommand.Answer(0, true), pending.expiresAt))
        val approved = approve()
        assertTrue(approved.permits(binding, now))
        assertFalse(approved.permits(binding, pending.expiresAt))
        assertNull(approved.reduce(ProcessDecisionCommand.Answer(approved.revision, true), now))
    }

    @Test
    fun `every changed binding field requires a new decision`() {
        val alternatives = listOf(
            binding.copy(harness = HarnessId("other")),
            binding.copy(item = ItemId("other")),
            binding.copy(harnessRevision = 2),
            binding.copy(process = binding.process.copy(process = ProcessId("other"))),
            binding.copy(process = binding.process.copy(incarnation = "reopened")),
            binding.copy(process = binding.process.copy(attempt = 2)),
            binding.copy(process = binding.process.copy(stage = StageName("release"))),
            binding.copy(inputRevision = 3),
            binding.copy(subject = SubjectSha("c".repeat(40))),
            binding.copy(workspace = WorkspaceRef("other")),
            binding.copy(repository = "https://github.com/owner/other"),
            binding.copy(operation = ProcessAuthority.Release),
            binding.copy(actionDigest = "d".repeat(64)),
        )
        for (changed in alternatives) {
            assertFalse(approve().permits(changed, now))
            assertNull(approve().reduce(ProcessDecisionCommand.Claim(changed), now))
        }
    }

    @Test
    fun `one approval admits one dispatch and completed retry cannot dispatch`() {
        val running = assertNotNull(approve().reduce(ProcessDecisionCommand.Claim(binding), now))
        assertEquals(ProcessDecisionPhase.Executing, running.phase)
        assertNull(running.reduce(ProcessDecisionCommand.Claim(binding), now))
        val completed = assertNotNull(running.reduce(ProcessDecisionCommand.Complete, now))
        assertEquals(ProcessDecisionPhase.Completed, completed.phase)
        assertNull(completed.reduce(ProcessDecisionCommand.Claim(binding), now))
        assertNull(completed.reduce(ProcessDecisionCommand.Complete, now))
    }

    @Test
    fun `refusal and revocation never become approval on replay`() {
        val denied = assertNotNull(pending.reduce(ProcessDecisionCommand.Answer(0, false), now))
        assertNull(denied.reduce(ProcessDecisionCommand.Answer(1, true), now))
        assertFalse(denied.permits(binding, now))
        val approved = approve()
        assertNull(approved.reduce(ProcessDecisionCommand.Revoke(0), now))
        val revoked = assertNotNull(approved.reduce(ProcessDecisionCommand.Revoke(1), now))
        assertFalse(revoked.permits(binding, now))
        assertNull(revoked.reduce(ProcessDecisionCommand.Answer(2, true), now))
        assertNull(revoked.reduce(ProcessDecisionCommand.Claim(binding), now))
    }

    @Test
    fun `admitted execution cannot falsely report successful revocation`() {
        val running = assertNotNull(approve().reduce(ProcessDecisionCommand.Claim(binding), now))
        assertNull(running.reduce(ProcessDecisionCommand.Revoke(running.revision), now))
        assertNull(running.reduce(ProcessDecisionCommand.Claim(binding), now))
        assertEquals(ProcessDecisionPhase.Executing, running.phase)
    }

    @Test
    fun `clock rollback cannot revive an expired decision`() {
        val approved = assertNotNull(pending.reduce(ProcessDecisionCommand.Answer(0, true), now + 10.seconds))
        assertFalse(approved.permits(binding, now))
        assertNull(approved.reduce(ProcessDecisionCommand.Claim(binding), now))
        assertNull(approved.reduce(ProcessDecisionCommand.Revoke(1), now))
    }

    @Test
    fun `restart retains pending refusal revocation and consumed dispatch`() {
        val approved = approve()
        val records = listOf(
            pending,
            approved,
            assertNotNull(pending.reduce(ProcessDecisionCommand.Answer(0, false), now)),
            assertNotNull(approved.reduce(ProcessDecisionCommand.Revoke(1), now)),
            assertNotNull(approved.reduce(ProcessDecisionCommand.Claim(binding), now)),
        )
        for (record in records) {
            val restored = Json.decodeFromString<ProcessDecision>(Json.encodeToString(record))
            assertEquals(record, restored)
            assertEquals(record.permits(binding, now), restored.permits(binding, now))
        }
    }

    private fun approve(): ProcessDecision = assertNotNull(pending.reduce(ProcessDecisionCommand.Answer(0, true), now))
}
