package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.impl.data.run.StoredWorkflowOrganismJournal
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageConflict
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageCorrupt
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.OrganismAdmission
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowOrganismGrant
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.workflowOrganism
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class StoredWorkflowOrganismJournalTest {
    private val clock = RunDeliveryClock()
    private val store = RunDeliveryTestStore(clock)
    private fun journal() = StoredWorkflowOrganismJournal(store, clock)

    @Test
    fun `an exact grant is recognized after a restart without taking another slot`() = runTest {
        val first = grant("wf_a", "s0")
        assertEquals(OrganismAdmission.Granted, journal().acquire(first))
        assertEquals(OrganismAdmission.Granted, journal().acquire(first))
        assertEquals(listOf(first), journal().pending())
    }

    @Test
    fun `a run owns one organism and the profile two until a confirmed end settles one`() = runTest {
        val journal = journal()
        val first = grant("wf_a", "s0")
        assertEquals(OrganismAdmission.Granted, journal.acquire(first))
        assertEquals(OrganismAdmission.RunLimit, journal.acquire(grant("wf_a", "s1")))
        assertEquals(OrganismAdmission.Granted, journal.acquire(grant("wf_b", "s0")))
        assertEquals(OrganismAdmission.ProfileLimit, journal.acquire(grant("wf_c", "s0")))
        assertEquals(2, journal.pending().size)
        // A restored grant is never refused by the quotas it already counts against.
        assertEquals(OrganismAdmission.Granted, journal.acquire(first))
        journal.settled(first)
        assertEquals(OrganismAdmission.Granted, journal.acquire(grant("wf_c", "s0")))
    }

    @Test
    fun `a changed grant a reused organism and a foreign settlement are conflicts`() = runTest {
        val journal = journal()
        val first = grant("wf_a", "s0")
        journal.acquire(first)
        assertFailsWith<HarnessStorageConflict> { journal.acquire(first.copy(digest = "b".repeat(64))) }
        assertFailsWith<HarnessStorageConflict> {
            journal.acquire(grant("wf_b", "s0").copy(organism = first.organism))
        }
        assertFailsWith<HarnessStorageConflict> { journal.settled(first.copy(digest = "b".repeat(64))) }
        journal.settled(first)
        journal.settled(first)
        assertTrue(journal.pending().isEmpty())
    }

    @Test
    fun `a corrupt aggregate fails closed without exposing its content`() = runTest {
        journal().acquire(grant("wf_a", "s0"))
        store.set(stringKey("grants"), "broken private record")
        val error = assertFailsWith<HarnessStorageCorrupt> { journal().pending() }
        assertFalse(error.toString().contains("private"))
        assertFailsWith<HarnessStorageCorrupt> { journal().acquire(grant("wf_b", "s0")) }
    }

    private fun grant(run: String, key: String) = WorkflowOrganismGrant(
        RunId(run),
        HarnessId("harness"),
        StepKey(key),
        "a".repeat(64),
        null,
        workflowOrganism(RunId(run), StepKey(key)) { it.encodeUtf8().sha256().hex() },
    )
}
