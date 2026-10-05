package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StudioWakeInboxTest {
    private val request = RequestId("request")

    @Test
    fun `confirmed cancellation clears only unsubmitted receipts`() = runTest {
        val stores = ChecklistTestStores()
        val inbox = StudioWakeInbox(stores)
        inbox.submitting(request)
        inbox.cancelledBeforeSubmission(request)
        assertNull(StudioWakeInbox(stores).receipt(request))
        inbox.submitting(request)
        inbox.accepted(request)
        inbox.cancelledBeforeSubmission(request)
        assertEquals(WakeReceipt.Accepted, StudioWakeInbox(stores).receipt(request))
    }

    @Test
    fun `wake inbox survives restart distinguishing unknown acceptance from accepted request`() = runTest {
        val stores = ChecklistTestStores()
        val inbox = StudioWakeInbox(stores)
        assertNull(inbox.receipt(request))
        inbox.submitting(request)
        assertEquals(WakeReceipt.Submitting, StudioWakeInbox(stores).receipt(request))
        assertFailsWith<IllegalStateException> { inbox.submitting(request) }
        inbox.accepted(request)
        assertEquals(WakeReceipt.Accepted, StudioWakeInbox(stores).receipt(request))
        assertNull(inbox.receipt(RequestId("explicit-new-attempt")))
    }
}
