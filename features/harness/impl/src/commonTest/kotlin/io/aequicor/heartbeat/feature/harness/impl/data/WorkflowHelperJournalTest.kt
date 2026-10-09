package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.impl.data.run.StoredWorkflowHelperJournal
import io.aequicor.heartbeat.feature.harness.impl.data.run.WorkflowHelperCapacityRecovery
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageConflict
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageCorrupt
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowHelperGrant
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class WorkflowHelperJournalTest {
    @Test
    fun `grant retry preserves bound helper and rejects conflicting identity`() = runTest {
        val clock = RunDeliveryClock()
        val store = RunDeliveryTestStore(clock)
        val journal = StoredWorkflowHelperJournal(store, clock)
        val grant = grant()
        journal.granted(grant)
        val bound = journal.bind(grant.reservation, HelperId("helper"))
        journal.granted(grant)
        assertEquals(listOf(bound), StoredWorkflowHelperJournal(store, clock).pending())
        assertFailsWith<HarnessStorageConflict> { journal.granted(grant.copy(request = RequestId("different"))) }
        assertFailsWith<HarnessStorageConflict> { journal.bind(grant.reservation, HelperId("different")) }
        assertFailsWith<HarnessStorageConflict> { journal.settled(grant) }
        journal.settled(bound)
        journal.settled(bound)
        assertTrue(journal.pending().isEmpty())
    }

    @Test
    fun `one step cannot restore two grants and one helper cannot claim two slots`() = runTest {
        val clock = RunDeliveryClock()
        val journal = StoredWorkflowHelperJournal(RunDeliveryTestStore(clock), clock)
        val grant = grant()
        journal.granted(grant)
        assertFailsWith<HarnessStorageConflict> { journal.granted(grant.copy(reservation = ActionId("other"))) }
        val second = grant.copy(reservation = ActionId("other"), key = StepKey("s1"))
        journal.granted(second)
        journal.bind(grant.reservation, HelperId("helper"))
        assertFailsWith<HarnessStorageConflict> { journal.bind(second.reservation, HelperId("helper")) }
    }

    @Test
    fun `capacity recovery includes preprompt slots and fails on corrupt aggregate`() = runTest {
        val clock = RunDeliveryClock()
        val store = RunDeliveryTestStore(clock)
        val journal = StoredWorkflowHelperJournal(store, clock)
        val grant = grant()
        journal.granted(grant)
        val source = WorkflowHelperCapacityRecovery(lazyOf(journal))
        val restored = source.reservations().single()
        assertEquals(grant.reservation, restored.reservation)
        assertEquals(grant.run.action, restored.owner)
        assertEquals(null, restored.helper)
        store.set(stringKey("grants"), "broken private record")
        assertFailsWith<HarnessStorageCorrupt> { source.reservations() }
    }

    private fun grant(): WorkflowHelperGrant = WorkflowHelperGrant(
        ActionId("slot"), RunId("wf_test"), HarnessId("harness"), StepKey("s0"), "a".repeat(64), null,
        RequestId("request"), RequestId("attach"),
    )
}
